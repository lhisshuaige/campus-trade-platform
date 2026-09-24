package com.campus.trade.service.imp;

import com.campus.trade.config.OrderTimeoutMqConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 订单超时检测消息。
 * 两条硬性规则：
 * 1) 只能在事务提交之后调用。事务内发消息 = 回滚了消息还在飞 = 30 分钟后取消一笔不存在的订单。
 *    本项目由 OrderTradeFacade 在 orderService.createOrder() 正常返回之后调用，天然满足。
 * 2) 发送失败绝不能让下单失败（订单已提交）。这里只记日志，
 *    真正的可靠性交给 OrderTimeoutFallbackJob 的兜底扫描 —— MQ 负责“及时”，扫描负责“不丢”。
 */
@Slf4j
@Component
public class OrderTimeoutProducer {

    @Resource
    private RabbitTemplate rabbitTemplate;

    //本机没装 RabbitMQ 时可关掉，避免日志刷满；关掉后超时取消完全由兜底扫描承担
    @Value("${app.order-timeout.mq-enabled:true}")
    private boolean mqEnabled;

    public void sendOrderTimeoutCheck(Long orderId) {
        if (orderId == null) {
            return;
        }
        if (!mqEnabled) {
            log.debug("MQ 已关闭，订单 {} 的超时取消交由兜底扫描处理", orderId);
            return;
        }
        try {
            //默认 SimpleMessageConverter 能直接序列化 Long；
            //生产环境建议换 Jackson2JsonMessageConverter（跨语言、不依赖 JDK 序列化、队列里可读）
            rabbitTemplate.convertAndSend(OrderTimeoutMqConfig.DELAY_EXCHANGE,
                    OrderTimeoutMqConfig.RK_DELAY, orderId);
        } catch (Exception e) {
            log.error("订单超时检测消息发送失败，等待兜底扫描 orderId={}", orderId, e);
        }
    }
}