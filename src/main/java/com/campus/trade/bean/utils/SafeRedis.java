package com.campus.trade.bean.utils;

import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 操作统一包装层：把「Redis 故障时怎么办」收口到一处，业务侧只负责声明降级方向。
 *
 * 判据只有一条：Redis 挂掉后，这条业务只靠 MySQL 还能不能【正确地】完成。
 * 1) 可降级（方法名 OrNull / Swallow）：catch + warn，返回中性值，业务继续跑。
 *    适用：缓存、限流、黑名单、缓存重建互斥锁 —— 可用性优先，坏了不该让业务停摆。
 *    返回 null 一律表示「不知道」（而非「没有」），由调用方决定 fail-open 还是 fail-close。
 * 2) 不降级（方法名 Required）：翻译成 BusinessException(503)，绝不吞掉。
 *    适用：交易锁、全局 ID 生成 —— 正确性优先，且下单链路对 Redis 是硬依赖（两处都用），
 *    明确失败好过"半可用"；也不让底层异常信息外泄（违反自身"异常不外泄"规范）。
 *
 * 日志纪律：只打 key 不打 value（value 可能是 token）；故障期间会刷 warn 日志，
 * 生产可换采样日志 / 接告警，本单体项目先保证「不变成 500」。
 */
@Slf4j
@Component
public class SafeRedis {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisConnectionFactory connectionFactory;

    @Resource
    private RedisCircuitBreaker circuitBreaker;

    // 与 RedissonClientHolder 对称的自愈：Lettuce 默认复用一条共享原生连接，
    // Redis 重启后这条坏连接可能被继续复用 → 表现就是"不重启应用就一直失败"。
    // 出现异常时主动 resetConnection()，下一条命令重新建连。1 秒内只重置一次，避免故障期被反复重建。
    private final AtomicLong lastResetAtMillis = new AtomicLong(0L);
    private static final long RESET_MIN_INTERVAL_MS = 1000L;

    private void resetConnectionQuietly() {
        if (!(connectionFactory instanceof LettuceConnectionFactory factory)) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastResetAtMillis.get();
        if (now - last < RESET_MIN_INTERVAL_MS || !lastResetAtMillis.compareAndSet(last, now)) {
            return;
        }
        try {
            factory.resetConnection();
            log.info("已重置 Lettuce 共享连接，下一条命令将重新建连");
        } catch (Exception e) {
            log.warn("重置 Lettuce 连接失败（忽略）", e);
        }
    }

    // ---------- 可降级：读 ----------

    /** 读缓存：失败当未命中，调用方回源 DB */
    public String getOrNull(String key) {
        return degrade(() -> stringRedisTemplate.opsForValue().get(key), "GET", key, null);
    }

    /** null = 未知（Redis 异常）；调用方必须显式决定 fail-open / fail-close */
    public Boolean hasKeyOrNull(String key) {
        return degrade(() -> stringRedisTemplate.hasKey(key), "EXISTS", key, null);
    }

    /** 自增计数：null = 未知，限流据此放行 */
    public Long incrOrNull(String key) {
        return degrade(() -> stringRedisTemplate.opsForValue().increment(key), "INCR", key, null);
    }

    /** SETNX：null = 未知，调用方一律按「没抢到锁」保守处理，不会造成并发双写 */
    public Boolean setIfAbsentOrNull(String key, String value, Duration ttl) {
        return degrade(() -> stringRedisTemplate.opsForValue().setIfAbsent(key, value, ttl), "SETNX", key, null);
    }

    /** 整张 hash：异常时给空 Map，调用方按「无增量」处理，不会去清 DB */
    public Map<Object, Object> hEntriesOrEmpty(String key) {
        Map<Object, Object> value = degrade(() -> stringRedisTemplate.opsForHash().entries(key),
                "HGETALL", key, null);
        return value == null ? new HashMap<>() : value;
    }

    // ---------- 可降级：写（失败只告警，不阻断业务） ----------

    /** @return true = 确实写进了 Redis；false = 被降级忽略。调用方需要“只有真失败才告警”时用它做判断 */
    public boolean setSwallow(String key, String value, Duration ttl) {
        return swallowQuietly(() -> stringRedisTemplate.opsForValue().set(key, value, ttl), "SET", key);
    }

    public boolean deleteSwallow(String key) {
        return swallowQuietly(() -> stringRedisTemplate.delete(key), "DEL", key);
    }

    public boolean expireSwallow(String key, Duration ttl) {
        return swallowQuietly(() -> stringRedisTemplate.expire(key, ttl), "EXPIRE", key);
    }

    /**
     * hash 域原子增减，返回变更后的累计值；null = 未知（异常/熔断）。
     * 适用浏览量这类「只关心总量、少计几条不致命」的计数，
     * 所以不做 Required 版本：不值得为展示型数据把详情接口做成 503
     */
    public Long hIncrByOrNull(String key, String field, long delta) {
        return degrade(() -> stringRedisTemplate.opsForHash().increment(key, field, delta), "HINCRBY", key, null);
    }

    /** 把已归集的增量扣回去（与上面同一个入口，传负数即扣减） */
    public void hIncrBySwallow(String key, String field, long delta) {
        swallowQuietly(() -> stringRedisTemplate.opsForHash().increment(key, field, delta), "HINCRBY", key);
    }

    /** 跑 Lua 等自定义操作时的降级入口（如 RedisLockUtils.unlock） */
    public void swallow(Runnable op, String opName, String key) {
        swallowQuietly(op, opName, key);
    }

    private boolean swallowQuietly(Runnable op, String opName, String key) {
        if (circuitBreaker.shouldSkip()) {
            log.debug("Redis [{}] 熔断跳过，直接忽略 key={}", opName, key);
            return false;
        }
        try {
            op.run();
            circuitBreaker.recordSuccess();
            return true;
        } catch (Exception e) {
            log.warn("Redis [{}] 失败，已忽略不阻断业务 key={}", opName, key, e);
            circuitBreaker.recordFailure("SafeRedis." + opName);
            resetConnectionQuietly();
            return false;
        }
    }

    // ---------- 不降级：翻译为明确业务码 ----------

    /** 计数器必须成功（全局 ID 生成）：失败即业务失败，返回 503 让调用方重试 */
    public Long incrRequired(String key) {
        // 熔断开路：一条命令都不发，直接 503。这是"下单点一次卡好几秒"的真正解药
        if (circuitBreaker.shouldSkip()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "系统繁忙，请稍后重试");
        }
        try {
            Long value = stringRedisTemplate.opsForValue().increment(key);
            circuitBreaker.recordSuccess();
            return value;
        } catch (Exception e) {
            log.error("Redis [INCR] 失败，该链路不降级 key={}", key, e);
            circuitBreaker.recordFailure("SafeRedis.incrRequired");
            resetConnectionQuietly();
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "系统繁忙，请稍后重试");
        }
    }

    private <T> T degrade(Supplier<T> op, String opName, String key, T fallback) {
        // 开路期间直接给降级值：不发命令 → 不超时 → 日志也不刷屏（故障期的主要噪音来源就是这里）
        if (circuitBreaker.shouldSkip()) {
            log.debug("Redis [{}] 熔断跳过，直接降级 key={}", opName, key);
            return fallback;
        }
        try {
            T value = op.get();
            circuitBreaker.recordSuccess();
            return value;
        } catch (Exception e) {
            log.warn("Redis [{}] 失败，降级为默认值 key={}", opName, key, e);
            circuitBreaker.recordFailure("SafeRedis." + opName);
            resetConnectionQuietly();
            return fallback;
        }
    }
}