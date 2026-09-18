package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.vo.request.order.OrderCreateVo;
import com.campus.trade.bean.vo.result.OrderVo;

public interface OrderService {
    void createOrder(OrderCreateVo vo, Long buyerId);
    void confirmOrder(Long orderId, Long sellerId);
    void completeOrder(Long orderId, Long sellerId, Long buyerId);
    void cancelOrder(Long orderId, Long userId);
    Page<OrderVo> getMyBuyOrders(Long userId, Integer pageNum, Integer pageSize);
    Page<OrderVo> getMySellOrders(Long userId, Integer pageNum, Integer pageSize);
}
