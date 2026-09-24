package com.campus.trade.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 订单超时取消的队列拓扑（TTL + 死信实现延迟）：
 *
 *   send ─► order.delay.queue（无人消费，TTL=30min）
 *                 │ 到期后按死信规则投递
 *                 ▼
 *   order.delay.exchange ─► order.timeout.queue ─► 消费者执行取消
 *                 │ 重试 3 次仍失败
 *                 ▼
 *              order.dlx.queue（人工/告警排查，绝不静默丢弃）
 *
 * 为什么不会死循环：timeout 队列被拒后路由到 order.dlx，而 dlx 队列没有消费者也不再转死信。
 *
 * ⚠️ TTL+DLX 只在【队头】消息到期时才检查，所以"全队列统一 TTL"才成立（我们固定 30 分钟）。
 *    将来若要"不同商品不同超时"，必须换 rabbitmq_delayed_message_exchange 插件，
 *    否则后到的短 TTL 消息会被队头长 TTL 消息阻塞 —— 这是死信做延迟最常见的翻车点。
 */
@Configuration
public class OrderTimeoutMqConfig {

    public static final String DELAY_EXCHANGE = "order.delay.exchange";
    public static final String DELAY_QUEUE = "order.delay.queue";
    public static final String TIMEOUT_QUEUE = "order.timeout.queue";
    public static final String DLX_QUEUE = "order.dlx.queue";

    public static final String RK_DELAY = "order.delay";
    public static final String RK_TIMEOUT = "order.timeout";
    public static final String RK_DLX = "order.dlx";

    @Value("${app.order-timeout.minutes:30}")
    private int timeoutMinutes;

    @Bean
    public DirectExchange orderDelayExchange() {
        return new DirectExchange(DELAY_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderDelayQueue() {
        return QueueBuilder.durable(DELAY_QUEUE)
                .ttl(timeoutMinutes * 60 * 1000)
                .deadLetterExchange(DELAY_EXCHANGE)
                .deadLetterRoutingKey(RK_TIMEOUT)
                .build();
    }

    @Bean
    public Queue orderTimeoutQueue() {
        return QueueBuilder.durable(TIMEOUT_QUEUE)
                .deadLetterExchange(DELAY_EXCHANGE)
                .deadLetterRoutingKey(RK_DLX)
                .build();
    }

    @Bean
    public Queue orderDlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE).build();
    }

    @Bean
    public Binding orderDelayBinding() {
        return BindingBuilder.bind(orderDelayQueue()).to(orderDelayExchange()).with(RK_DELAY);
    }

    @Bean
    public Binding orderTimeoutBinding() {
        return BindingBuilder.bind(orderTimeoutQueue()).to(orderDelayExchange()).with(RK_TIMEOUT);
    }

    @Bean
    public Binding orderDlxBinding() {
        return BindingBuilder.bind(orderDlxQueue()).to(orderDelayExchange()).with(RK_DLX);
    }
}