package com.campus.trade.service.imp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.trade.bean.entry.Order;
import com.campus.trade.mapper.OrderMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时未确认订单的兜底扫描。
 * 与 MQ 并存不冲突：cancelOrder 内部全是 CAS + 分布式锁，两边同时命中同一订单也只有一次能成功。
 * 这是“最终一致”的常规做法 —— 及时通道用 MQ，正确性通道用对账扫描。
 */
@Slf4j
@Component
public class OrderTimeoutFallbackJob {

    @Resource
    private OrderMapper orderMapper;
    @Resource
    private OrderTradeFacade orderTradeFacade;

    @Value("${app.order-timeout.minutes:30}")
    private int timeoutMinutes;

    //fixedDelay：上一次执行完再计时，避免任务自身堆积
    @Scheduled(fixedDelayString = "${app.order-timeout.scan-interval-ms:300000}", initialDelay = 60_000)
    public void cancelStuckOrders() {
        List<Order> stuck = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, Order.STATUS_PENDING)
                .lt(Order::getCreateTime, LocalDateTime.now().minusMinutes(timeoutMinutes))
                .last("LIMIT 200"));   //单轮限量，避免长时间故障后一次性捞全表
        if (stuck.isEmpty()) {
            return;
        }
        log.info("兜底扫描发现 {} 条超时未确认订单", stuck.size());
        for (Order order : stuck) {
            try {
                orderTradeFacade.cancelOrder(order.getId(), order.getBuyerId());
                log.info("兜底取消超时订单成功 orderId={}", order.getId());
            } catch (Exception e) {
                //单条失败必须吞掉，否则一条毒数据中断整批对账
                log.warn("兜底取消失败 orderId={}", order.getId(), e);
            }
        }
    }
}