package com.campus.trade.config;

import com.campus.trade.bean.utils.RedisCircuitBreaker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * 把 {@link RedisCircuitBreaker} 的状态映射成 actuator 健康项（P3-17）。
 *
 * <p><b>它和 Spring Boot 自带的那个 {@code redis} 健康项不是重复的，而是回答两个不同的问题</b>：
 * <ul>
 *   <li>{@code redis}（{@code RedisHealthContributorAutoConfiguration} 提供）：发一条真 PING，
 *       回答「Redis <b>此刻</b>通不通」；</li>
 *   <li>{@code redisCircuit}（本类）：读熔断器的判定，回答「<b>应用</b>现在怎么对待 Redis」——
 *       开路期间缓存直接回源、下单直接 503，此时哪怕 PING 得通，用户感受到的也是降级。</li>
 * </ul>
 * 两者不一致恰恰是最需要被看见的窗口（最长 30s）：只看 {@code redis} 会得出「健康检查绿着但下不了单」
 * 这种没人能解释的现象。所以本类的 {@code DOWN} 语义不是「Redis 坏了」，而是「这个实例正在拒绝走缓存路径」。
 *
 * <p><b>它被放进 readiness、没有被放进 liveness</b>（分组见 application.yaml 的 management 段）：
 * 熔断是一次<b>可自愈</b>的状态，30 秒后自己会去试探；而 liveness 失败的含义是「这个进程救不回来，杀掉重启」。
 * 把可自愈的降级接到重启上，等于让一次 Redis 抖动把所有实例同时重启，重启完再一起把 Redis 打成惊群。
 *
 * <p>状态取值刻意只用 {@link Status#UP} 与 {@link Status#DOWN}：自定义一个 {@code DEGRADED} 看着更贴切，
 * 但 {@code management.endpoint.health.status.order} 默认不认识它，聚合时它会被排在 UP <b>之后</b>——
 * 结果是「熔断器开路，聚合健康仍是 UP」，一个静默失效的探针比没有探针更危险。
 */
@Component
public class RedisCircuitHealthIndicator implements HealthIndicator {

    private final RedisCircuitBreaker circuitBreaker;

    public RedisCircuitHealthIndicator(RedisCircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public Health health() {
        RedisCircuitBreaker.State state = circuitBreaker.state();
        // 三个数字都比「OPEN」这个词有用：排查时要判断的是「还剩多久恢复试探」和「退避已经抬到多大」，
        // 后者能直接看出这次故障已经持续了几轮
        Health.Builder builder = (state == RedisCircuitBreaker.State.OPEN) ? Health.down() : Health.up();
        return builder
                .withDetail("state", state.name())
                .withDetail("consecutiveFailures", circuitBreaker.consecutiveFailures())
                .withDetail("openRemainingMillis", circuitBreaker.openRemainingMillis())
                .withDetail("currentOpenWindowMillis", circuitBreaker.currentOpenWindowMillis())
                .build();
    }
}
