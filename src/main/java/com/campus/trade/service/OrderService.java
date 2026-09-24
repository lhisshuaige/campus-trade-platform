package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.DTO.request.order.OrderCreateDTO;
import com.campus.trade.bean.vo.OrderVo;

public interface OrderService {
    //返回新订单 id：facade 需要在【事务提交之后】拿它去发超时消息，返回 void 就只能再查一次库
    Long createOrder(OrderCreateDTO vo, Long buyerId);
    void confirmOrder(Long orderId, Long sellerId);
    //完成交易=买家确认收货，只允许买家触发
    void completeOrder(Long orderId, Long buyerId);
    void cancelOrder(Long orderId, Long userId);
    Page<OrderVo> getMyBuyOrders(Long userId, Integer pageNum, Integer pageSize);
    Page<OrderVo> getMySellOrders(Long userId, Integer pageNum, Integer pageSize);
}
