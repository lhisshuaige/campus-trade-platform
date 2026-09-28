package com.campus.trade.bean.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 极简 Redis 熔断：回答一个问题——「现在还要不要真的往 Redis 发命令」。
 *
 * 为什么必须有它：超时参数只管"一条命令最多等多久"，Redis 黑洞时每个请求仍要各吃一个 timeout。
 * 下单链路上光是 JwtInterceptor 就要发 EXISTS + GET (+ 回填 SET)，3 秒超时 × 3 = 用户点一次卡 9 秒，
 * 这时候你在 Redisson 侧做的"建连冷却"根本被掩盖住了 —— 看起来就像没生效。
 * 熔断的意义是把「N 个请求各等一次超时」收敛成「1 个请求等超时 + 其余 0 超时直接降级」。
 *
 * 语义：
 * - 连续失败达阈值 → 开路 openMillis，期间 shouldSkip() 为 true，调用方直接走降级/快速失败；
 * - 开路期内重复失败【不再延长窗口】，否则一次长故障会让窗口被无限续期、永远好不了；
 * - 窗口到期后放行真实请求去试探（半开），再失败则立刻以一个【翻倍的新窗口】开路；
 * - 任何一次成功（Lettuce 或 Redisson 都行，因为底层是同一个 Redis）立即闭合。
 * 已知边界：窗口到期瞬间若并发很高，会有一批请求同时去试探（惊群）。本项目 QPS 极低可忽略，
 * 真要收敛就加 CAS 半开闸门，代价是多一层状态，这里选择不加。
 */
@Slf4j
@Component
public class RedisCircuitBreaker {

    // 连续失败 3 次才开路：避免偶发的一次抖动就把 Redis 判死
    private static final int FAILURE_THRESHOLD = 3;
    private static final long OPEN_MIN_MILLIS = 3000L;
    private static final long OPEN_MAX_MILLIS = 30_000L;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntilMillis = new AtomicLong(0L);
    private final AtomicLong openMillis = new AtomicLong(OPEN_MIN_MILLIS);

    /** true = 当前认为 Redis 不可用，调用方别发真命令（直接降级 / 直接 503） */
    public boolean shouldSkip() {
        return System.currentTimeMillis() < openUntilMillis.get();
    }

    /** 是否处于开路状态（供健康检查/探测任务判断） */
    public boolean isTripped() {
        return shouldSkip();
    }

    public void recordSuccess() {
        if (consecutiveFailures.get() == 0 && openUntilMillis.get() == 0) {
            return;   // 稳态零开销
        }
        consecutiveFailures.set(0);
        openUntilMillis.set(0L);
        openMillis.set(OPEN_MIN_MILLIS);
        log.info("Redis 恢复，熔断闭合");
    }

    public void recordFailure(String where) {
        int failures = consecutiveFailures.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now < openUntilMillis.get()) {
            // 已在开路期内，不重复延长
            return;
        }
        if (failures < FAILURE_THRESHOLD) {
            return;
        }
        long open = openMillis.get();
        openUntilMillis.set(now + open);
        // 翻倍退避：长时间故障下试探次数对数级增长，不会一直拿用户请求去撞超时
        openMillis.set(Math.min(open * 2, OPEN_MAX_MILLIS));
        log.warn("Redis 连续 {} 次失败(最近:{})，熔断开路 {}ms；期间缓存直接回源、下单直接 503，不再吃连接超时",
                failures, where, open);
    }
}