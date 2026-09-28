package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.DTO.request.order.OrderAdminQueryDTO;
import com.campus.trade.bean.DTO.request.order.OrderCreateDTO;
import com.campus.trade.bean.vo.OrderVo;

public interface OrderService {
    //下单返回完整订单视图：前端拿 orderNo 才能直接展示"下单成功+订单号"，
    //只回 id 就得再调一次详情。facade 仍从 vo.getId() 取 id 去发超时消息，时序不变
    OrderVo createOrder(OrderCreateDTO vo, Long buyerId);
    void confirmOrder(Long orderId, Long sellerId);
    //完成交易=买家确认收货，只允许买家触发
    void completeOrder(Long orderId, Long buyerId);
    void cancelOrder(Long orderId, Long userId);
    //订单详情：只有买卖双方能看；管理员看订单走 /admin/order/page
    OrderVo getOrderDetail(Long orderId, Long loginUserId);
    //status 传 null 表示不限状态
    Page<OrderVo> getMyBuyOrders(Long userId, Integer status, Integer pageNum, Integer pageSize);
    Page<OrderVo> getMySellOrders(Long userId, Integer status, Integer pageNum, Integer pageSize);
    //管理端订单查询：跨用户，可按状态/订单号/买卖家/商品筛
    Page<OrderVo> getOrderPageForAdmin(OrderAdminQueryDTO queryDTO);
}
