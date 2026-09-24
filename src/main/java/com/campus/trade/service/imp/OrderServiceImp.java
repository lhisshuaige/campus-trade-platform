package com.campus.trade.service.imp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.entry.Goods;
import com.campus.trade.bean.entry.Order;
import com.campus.trade.bean.entry.User;
import com.campus.trade.bean.utils.CacheUtils;
import com.campus.trade.bean.utils.RedisContent;
import com.campus.trade.bean.utils.RedisWorker;
import com.campus.trade.bean.DTO.request.order.OrderCreateDTO;
import com.campus.trade.bean.vo.OrderVo;
import com.campus.trade.mapper.GoodsMapper;
import com.campus.trade.mapper.OrderMapper;
import com.campus.trade.mapper.UserMapper;
import com.campus.trade.service.OrderService;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class OrderServiceImp extends ServiceImpl<OrderMapper, Order> implements OrderService {

    @Resource
    private GoodsMapper goodsMapper;
    @Resource
    private UserMapper userMapper;
    @Resource
    private RedisWorker redisWorker;
    @Resource
    private CacheUtils cacheUtils;

    /**
     * 下单：只负责“事务内的正确性”。
     * 分布式锁在 OrderTradeFacade（锁必须包住事务，否则 unlock 到 commit 之间的窗口里
     * 别人能拿到锁并读到提交前的旧数据，临界区形同虚设）。
     * 这里去掉锁也不会超卖，因为下面的 CAS 才是正确性底线。
     */
    @Override
    public Long createOrder(OrderCreateDTO vo, Long buyerId) {
        Goods goods = goodsMapper.selectById(vo.getGoodsId());
        if (goods == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        if (goods.getStatus() != Goods.STATUS_ON_SALE) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "该商品当前不可购买");
        }
        if (goods.getUserId().equals(buyerId)) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "不能购买自己的商品");
        }

        // 原子抢占：仅当仍在售(1)才改为已售(2)；受影响行数=0 说明已被抢走（真正的正确性保证）
        LambdaUpdateWrapper<Goods> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Goods::getId, goods.getId())
                .eq(Goods::getStatus, Goods.STATUS_ON_SALE)
                .set(Goods::getStatus, Goods.STATUS_SOLD);
        if (goodsMapper.update(null, updateWrapper) == 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "该商品已被他人下单，请刷新后重试");
        }
        // 商品状态 1(在售)->2(已售)，详情缓存必须失效，否则别人还能看到"可购买"
        cacheUtils.evictAfterCommit(RedisContent.goodsDetailKey(goods.getId()));

        String orderNo = String.valueOf(redisWorker.nextId(RedisContent.Order_NO_KEY));

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setGoodsId(goods.getId());
        order.setSellerId(goods.getUserId());
        order.setBuyerId(buyerId);
        order.setPrice(goods.getPrice());
        order.setStatus(Order.STATUS_PENDING);
        save(order);
        return order.getId();
    }

    //  卖家确认订单：0 待确认 -> 1 已确认
    @Override
    public void confirmOrder(Long orderId, Long sellerId) {
        Order order = getById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!order.getSellerId().equals(sellerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有卖家可以确认该订单");
        }
        //预检只为给用户可读提示；并发下的正确性靠下面这条带状态条件的 UPDATE
        if (order.getStatus() != Order.STATUS_PENDING) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "当前订单状态不允许此操作");
        }
        // CAS：影响行数 0 说明这一瞬间订单已被取消/已确认，读-判-写窗口被兜住了
        LambdaUpdateWrapper<Order> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Order::getId, orderId)
                .eq(Order::getStatus, Order.STATUS_PENDING)
                .set(Order::getStatus, Order.STATUS_CONFIRMED);
        if (baseMapper.update(null, wrapper) == 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "订单状态已变更，请刷新后重试");
        }
    }

    // 买家确认收货：1 已确认 -> 2 已完成
    @Override
    public void completeOrder(Long orderId, Long buyerId) {
        Order order = getById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        //完成动作只允许买家触发：卖家意愿已在 confirmOrder 表达
        //一个状态迁移只有一个角色能推进，否则会出现"买家单方宣布交易完成"
        if (!order.getBuyerId().equals(buyerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有买家可以确认收货完成交易");
        }
        //必须是"卖家已确认"状态才能完成，防止跳过卖家确认直接完成
        if (order.getStatus() != Order.STATUS_CONFIRMED) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "卖家尚未确认订单或该订单已结束，无法完成");
        }
        LambdaUpdateWrapper<Order> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Order::getId, orderId)
                .eq(Order::getStatus, Order.STATUS_CONFIRMED)
                .set(Order::getStatus, Order.STATUS_COMPLETED);
        if (baseMapper.update(null, wrapper) == 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "订单状态已变更，请刷新后重试");
        }
    }


    /**
     * 取消订单：0/1 -> 3，并释放被本单占用的商品。
     * 加锁由 OrderTradeFacade 负责；本方法内的 CAS 保证即使没抢到锁（如别人绕过 facade 直调）也不会写错。
     */
    @Override
    public void cancelOrder(Long orderId, Long userId) {
        Order order = getById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        //取消是双方对称的权利（谁不想交易了都能撤），这点和"完成只能买家"不同
        if (!order.getSellerId().equals(userId) && !order.getBuyerId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作此订单");
        }
        if (order.getStatus() == Order.STATUS_COMPLETED || order.getStatus() == Order.STATUS_CANCELLED) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "当前订单状态不允许取消");
        }

        // CAS：仅当仍处于 0待确认/1已确认 才置为 3已取消
        LambdaUpdateWrapper<Order> orderWrapper = new LambdaUpdateWrapper<>();
        orderWrapper.eq(Order::getId, orderId)
                .in(Order::getStatus, Order.STATUS_PENDING, Order.STATUS_CONFIRMED)
                .set(Order::getStatus, Order.STATUS_CANCELLED);
        if (baseMapper.update(null, orderWrapper) == 0) {
            //抢不到状态迁移就结束，绝不能继续去动商品状态
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "订单状态已变更，请刷新后重试");
        }

        // 条件恢复上架：只有仍停在"已售出(2)"才改回"在售(1)"
        // 若卖家期间已把商品下架(0)，那是卖家的新意愿，不应被取消订单覆盖回上架
        LambdaUpdateWrapper<Goods> goodsWrapper = new LambdaUpdateWrapper<>();
        goodsWrapper.eq(Goods::getId, order.getGoodsId())
                .eq(Goods::getStatus, Goods.STATUS_SOLD)
                .set(Goods::getStatus, Goods.STATUS_ON_SALE);
        if (goodsMapper.update(null, goodsWrapper) > 0) {
            cacheUtils.evictAfterCommit(RedisContent.goodsDetailKey(order.getGoodsId()));
        }
    }

    // 分页查询我的购买订单
    @Override
    public Page<OrderVo> getMyBuyOrders(Long userId, Integer pageNum, Integer pageSize) {
        pageNum = Math.max(1, pageNum);
        pageSize = Math.min(Math.max(1, pageSize), 50);

        Page<Order> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getBuyerId, userId);
        wrapper.orderByDesc(Order::getCreateTime);
        Page<Order> orderPage = page(page, wrapper);

        return convertToVoPage(orderPage);
    }

    // 分页查询我的卖出订单
    @Override
    public Page<OrderVo> getMySellOrders(Long userId, Integer pageNum, Integer pageSize) {
        pageNum = Math.max(1, pageNum);
        pageSize = Math.min(Math.max(1, pageSize), 50);

        Page<Order> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getSellerId, userId);
        wrapper.orderByDesc(Order::getCreateTime);
        Page<Order> orderPage = page(page, wrapper);

        return convertToVoPage(orderPage);
    }

    // 将Order分页结果转换为OrderVo分页结果
    private Page<OrderVo> convertToVoPage(Page<Order> orderPage) {
        List<Order> orderList = orderPage.getRecords();
        if (orderList.isEmpty()) {
            Page<OrderVo> voPage = new Page<>(orderPage.getCurrent(), orderPage.getSize(), orderPage.getTotal());
            voPage.setRecords(new ArrayList<>());
            return voPage;
        }

        // 批量查询商品信息，避免N+1
        List<Long> goodsIds = orderList.stream().map(Order::getGoodsId).distinct().toList();
        List<Goods> goodsList = goodsMapper.selectBatchIds(goodsIds);
        Map<Long, Goods> goodsMap = goodsList.stream().collect(Collectors.toMap(Goods::getId, g -> g));

        // 批量查询买卖双方昵称，避免N+1
        List<Long> userIds = new ArrayList<>();
        for (Order o : orderList) {
            userIds.add(o.getSellerId());
            userIds.add(o.getBuyerId());
        }
        userIds = userIds.stream().distinct().toList();
        List<User> users = userMapper.selectBatchIds(userIds);
        Map<Long, String> nicknameMap = users.stream()
                .collect(Collectors.toMap(User::getId, u -> u.getNickname() != null ? u.getNickname() : ""));

        List<OrderVo> voList = new ArrayList<>();
        for (Order order : orderList) {
            OrderVo vo = new OrderVo();
            BeanUtils.copyProperties(order, vo);
            Goods goods = goodsMap.get(order.getGoodsId());
            if (goods != null) {
                vo.setGoodsTitle(goods.getTitle());
                vo.setGoodsImage(goods.getImgUrl());
            }
            vo.setSellerNickname(nicknameMap.getOrDefault(order.getSellerId(), ""));
            vo.setBuyerNickname(nicknameMap.getOrDefault(order.getBuyerId(), ""));
            voList.add(vo);
        }

        Page<OrderVo> voPage = new Page<>(orderPage.getCurrent(), orderPage.getSize(), orderPage.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }
}
