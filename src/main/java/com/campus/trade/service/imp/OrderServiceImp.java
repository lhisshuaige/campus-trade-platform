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
import com.campus.trade.bean.utils.RedisContent;
import com.campus.trade.bean.utils.RedisLockUtils;
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

import java.time.Duration;
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
    private RedisLockUtils redisLockUtils;
    @Resource
    private RedisWorker redisWorker;

    @Override
    public void createOrder(OrderCreateDTO vo, Long buyerId) {
        // 1. 对该商品加分布式锁，避免并发下单
        String lockKey = RedisContent.Goods_Lock_KEY + vo.getGoodsId();
        String lockValue = redisLockUtils.tryLock(lockKey, Duration.ofSeconds(10));
        if (lockValue == null) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "系统繁忙，请稍后重试");
        }
        try {
            Goods goods = goodsMapper.selectById(vo.getGoodsId());
            if (goods == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在");
            }
            if (goods.getStatus() != 1) {
                throw new BusinessException(ErrorCode.BUSINESS_ERROR, "该商品当前不可购买");
            }
            if (goods.getUserId().equals(buyerId)) {
                throw new BusinessException(ErrorCode.BUSINESS_ERROR, "不能购买自己的商品");
            }

            // 2. 原子抢占：仅当仍在售(1)才改为已售(2)；受影响行数=0 说明已被抢走（真正的正确性保证）
            LambdaUpdateWrapper<Goods> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(Goods::getId, goods.getId())
                    .eq(Goods::getStatus, 1)
                    .set(Goods::getStatus, 2);
            int rows = goodsMapper.update(null, updateWrapper);
            if (rows == 0) {
                throw new BusinessException(ErrorCode.BUSINESS_ERROR, "该商品已被他人下单，请刷新后重试");
            }

            String orderNo = String.valueOf(redisWorker.nextId(RedisContent.Order_NO_KEY));

            Order order = new Order();
            order.setOrderNo(orderNo);
            order.setGoodsId(goods.getId());
            order.setSellerId(goods.getUserId());
            order.setBuyerId(buyerId);
            order.setPrice(goods.getPrice());
            order.setStatus(0);
            save(order);
        } finally {
            // 3. 释放锁（Lua 校验持有者，避免误删别人的锁）
            redisLockUtils.unlock(lockKey, lockValue);
        }
    }

    //  卖家确认订单
    @Override
    public void confirmOrder(Long orderId, Long sellerId) {
        Order order = getById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!order.getSellerId().equals(sellerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作此订单");
        }
        if (order.getStatus() != 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "当前订单状态不允许此操作");
        }
        order.setStatus(1);
        updateById(order);
    }

    // 卖家确认商品已经卖出
    @Override
    public void completeOrder(Long orderId, Long sellerId, Long buyerId) {
        Order order = getById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!order.getSellerId().equals(sellerId) && !order.getBuyerId().equals(buyerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作此订单");
        }
        if (order.getStatus() != 1) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "当前订单状态不允许此操作");
        }
        order.setStatus(2);
        updateById(order);
    }


    @Override
    public void cancelOrder(Long orderId, Long userId) {
        Order order = getById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!order.getSellerId().equals(userId) && !order.getBuyerId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作此订单");
        }
        if (order.getStatus() == 2 || order.getStatus() == 3) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "当前订单状态不允许取消");
        }
        order.setStatus(3);
        updateById(order);

        // 取消订单后恢复商品上架
        Goods goods = goodsMapper.selectById(order.getGoodsId());
        if (goods != null) {
            goods.setStatus(1);
            goodsMapper.updateById(goods);
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
