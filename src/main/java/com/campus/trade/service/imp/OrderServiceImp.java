package com.campus.trade.service.imp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.entry.Goods;
import com.campus.trade.bean.entry.Notice;
import com.campus.trade.bean.entry.Order;
import com.campus.trade.bean.entry.User;
import com.campus.trade.bean.utils.CacheUtils;
import com.campus.trade.bean.utils.RedisContent;
import com.campus.trade.bean.utils.RedisWorker;
import com.campus.trade.bean.DTO.request.order.OrderAdminQueryDTO;
import com.campus.trade.bean.DTO.request.order.OrderCreateDTO;
import com.campus.trade.bean.vo.OrderVo;
import com.campus.trade.mapper.GoodsMapper;
import com.campus.trade.mapper.OrderMapper;
import com.campus.trade.mapper.UserMapper;
import com.campus.trade.service.NoticeService;
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
    //站内信：只在订单状态真的迁移成功后才发，所以全部写在 CAS 判断之后
    @Resource
    private NoticeService noticeService;

    /**
     * 下单：只负责“事务内的正确性”。
     * 分布式锁在 OrderTradeFacade（锁必须包住事务，否则 unlock 到 commit 之间的窗口里
     * 别人能拿到锁并读到提交前的旧数据，临界区形同虚设）。
     * 这里去掉锁也不会超卖，因为下面的 CAS 才是正确性底线。
     */
    @Override
    public OrderVo createOrder(OrderCreateDTO vo, Long buyerId) {
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
        // 榜单只统计在售，卖掉了还挂在榜上会误导新买家
        cacheUtils.evictAfterCommit(RedisContent.Collect_Rank_KEY);

        String orderNo = String.valueOf(redisWorker.nextId(RedisContent.Order_NO_KEY));

        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setGoodsId(goods.getId());
        order.setSellerId(goods.getUserId());
        order.setBuyerId(buyerId);
        order.setPrice(goods.getPrice());
        order.setStatus(Order.STATUS_PENDING);
        save(order);
        //通知卖家“有人拍下了”。文案里带商品标题与订单号，就不需要为了“是哪一笔”再查一次买家昵称：
        //昵称在订单详情里就有，前端点进去一次就能拿到，不值得为它在下单路径上多一次主键查询
        noticeService.send(goods.getUserId(), buyerId, Notice.TYPE_ORDER,
                "有人拍下了你的商品",
                "商品《" + goods.getTitle() + "》已生成订单 " + orderNo + "，请及时确认",
                order.getId());
        //组装出参（走 toVo → toVoList 唯一实现）：多一次 goods/user 查询，但下单是低频写路径，
        //不值得为它写第二套组装逻辑
        return toVo(order);
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
        //CAS 命中才通知：没抢到迁移的事务其实什么都没改，不能给买家发一条“卖家已确认”的假消息
        noticeService.send(order.getBuyerId(), sellerId, Notice.TYPE_ORDER,
                "卖家已确认订单",
                "商品《" + goodsTitleOf(order) + "》卖家已确认，可约时间当面交易，收货后请确认完成",
                orderId);
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
        //交易完成对卖家是有结果的事件（能拿到钱），必须通知，而原来只有买家知道自己点了“完成”
        noticeService.send(order.getSellerId(), buyerId, Notice.TYPE_ORDER,
                "买家已确认收货",
                "商品《" + goodsTitleOf(order) + "》交易已完成，订单号 " + order.getOrderNo(),
                orderId);
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
            cacheUtils.evictAfterCommit(RedisContent.Collect_Rank_KEY);
        }
        //取消是双方对称的权利，所以另外一方必须被告知（不然另一个人还以为交易在走）；
        //文案里写清是谁取消的，同样一件事对两边的下一步完全不同
        boolean byBuyer = order.getBuyerId().equals(userId);
        Long counterparty = byBuyer ? order.getSellerId() : order.getBuyerId();
        noticeService.send(counterparty, userId, Notice.TYPE_ORDER,
                "订单已取消",
                "商品《" + goodsTitleOf(order) + "》的订单已由" + (byBuyer ? "买家" : "卖家")
                        + "取消，订单号 " + order.getOrderNo(),
                orderId);
    }

    //通知文案里的商品名：一次主键命中换一条“看得懂是哪笔交易”的消息，
    //订单状态流转是低频写路径，这个成本可接受（fk_order_goods 是 RESTRICT，
    //有订单的商品根本删不掉，所以查不到只是理论上的兼容分支）
    private String goodsTitleOf(Order order) {
        Goods goods = goodsMapper.selectById(order.getGoodsId());
        return goods != null ? goods.getTitle() : "已下架商品";
    }

    // 分页查询我的购买订单
    @Override
    public Page<OrderVo> getMyBuyOrders(Long userId, Integer status, Integer pageNum, Integer pageSize) {
        return pageMyOrders(Order::getBuyerId, userId, status, pageNum, pageSize);
    }

    // 分页查询我的卖出订单
    @Override
    public Page<OrderVo> getMySellOrders(Long userId, Integer status, Integer pageNum, Integer pageSize) {
        return pageMyOrders(Order::getSellerId, userId, status, pageNum, pageSize);
    }

    /**
     * 我的订单列表的唯一实现：买家/卖家两个入口只差一列，
     * 分页参数夹紧和状态取值域校验也只写一处（原来两处各夹一遍，改一处漏一处）
     */
    private Page<OrderVo> pageMyOrders(SFunction<Order, ?> roleColumn, Long userId, Integer status,
                                       Integer pageNum, Integer pageSize) {
        checkStatus(status);
        long current = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        long size = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 50);

        Page<Order> page = new Page<>(current, size);
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(roleColumn, userId);
        wrapper.eq(status != null, Order::getStatus, status);
        wrapper.orderByDesc(Order::getCreateTime);
        return convertToVoPage(page(page, wrapper));
    }

    //管理端订单查询
    @Override
    public Page<OrderVo> getOrderPageForAdmin(OrderAdminQueryDTO queryDTO) {
        checkStatus(queryDTO.getStatus());
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(queryDTO.getStatus() != null, Order::getStatus, queryDTO.getStatus());
        wrapper.eq(queryDTO.getBuyerId() != null, Order::getBuyerId, queryDTO.getBuyerId());
        wrapper.eq(queryDTO.getSellerId() != null, Order::getSellerId, queryDTO.getSellerId());
        wrapper.eq(queryDTO.getGoodsId() != null, Order::getGoodsId, queryDTO.getGoodsId());
        //订单号是"用户报给客服的一串数字"，精确匹配才可靠，模糊会命中一批
        wrapper.eq(queryDTO.getOrderNo() != null && !queryDTO.getOrderNo().isBlank(),
                Order::getOrderNo, queryDTO.getOrderNo());
        wrapper.orderByDesc(Order::getCreateTime);
        return convertToVoPage(page(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper));
    }

    //订单详情：只允许交易当事人查看
    @Override
    public OrderVo getOrderDetail(Long orderId, Long loginUserId) {
        Order order = getById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        //详情里有价格和双方身份，水平越权就在这一步拦：
        //id 是自增的，不校验就能枚举别人的交易
        if (!order.getBuyerId().equals(loginUserId) && !order.getSellerId().equals(loginUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看该订单");
        }
        return toVo(order);
    }

    //取值域与 init.sql 的 chk_order_status 一致；不校验就带着非法 status 打到 DB CHECK → 500
    private void checkStatus(Integer status) {
        if (status == null) {
            return;
        }
        if (status < Order.STATUS_PENDING || status > Order.STATUS_CANCELLED) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "订单状态取值只能是 0待确认/1已确认/2已完成/3已取消");
        }
    }

    // 将Order分页结果转换为OrderVo分页结果
    private Page<OrderVo> convertToVoPage(Page<Order> orderPage) {
        Page<OrderVo> voPage = new Page<>(orderPage.getCurrent(), orderPage.getSize(), orderPage.getTotal());
        voPage.setRecords(toVoList(orderPage.getRecords()));
        return voPage;
    }

    //单笔读（详情、下单返回）也走批量实现，不再养第二套组装逻辑
    private OrderVo toVo(Order order) {
        return toVoList(List.of(order)).get(0);
    }

    // Order -> OrderVo 的唯一组装实现：批量查商品与双方昵称，避免 N+1
    private List<OrderVo> toVoList(List<Order> orderList) {
        if (orderList.isEmpty()) {
            return new ArrayList<>();
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
        return voList;
    }
}
