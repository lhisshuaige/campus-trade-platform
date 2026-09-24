package com.campus.trade.service.imp;

import com.campus.trade.bean.entry.Order;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.config.OrderTimeoutMqConfig;
import com.campus.trade.mapper.OrderMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费超时消息，执行自动取消。
 *
 * 幂等从哪来？不靠去重表，靠上一轮刚做的状态机 CAS：
 *   重复消费 → 订单已是 3 → WHERE status IN (0,1) 命中 0 行 → 抛 BusinessException → 这里 catch 掉当成功。
 * 所以"引入 MQ 后如何保证幂等"这个追问，答案就在这个文件里。
 *
 * 为什么不从消息里带 userId？消息内容一律不可信（可伪造、可过期、状态早变了），
 * 权限判定必须以库里的订单为准 —— 这里用 buyer 身份发起，与买家手工取消等价。
 */
@Slf4j
@Component
public class OrderTimeoutListener {

    @Resource
    private OrderMapper orderMapper;
    @Resource
    private OrderTradeFacade orderTradeFacade;

    @RabbitListener(queues = OrderTimeoutMqConfig.TIMEOUT_QUEUE)
    public void onTimeout(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            log.info("订单已不存在，丢弃超时消息 orderId={}", orderId);
            return;
        }
        if (order.getStatus() != Order.STATUS_PENDING) {
            //卖家已确认/已完成/已取消，无需处理，直接 ACK
            log.info("订单状态非待确认，跳过超时取消 orderId={} status={}", orderId, order.getStatus());
            return;
        }
        try {
            orderTradeFacade.cancelOrder(orderId, order.getBuyerId());
            log.info("订单超时自动取消成功 orderId={}", orderId);
        } catch (BusinessException e) {
            //业务性失败（并发下已被处理、锁被占用）重投也还是失败 → 记日志放过，避免毒消息循环
            log.info("超时取消跳过 orderId={} reason={}", orderId, e.getMessage());
        }
        //注意：这里【不要】catch 系统异常。DB 不可用等必须往外抛，
        //交给 Spring AMQP 的 retry(3 次) + DLX，否则真正的故障会被静默吞掉
    }
}