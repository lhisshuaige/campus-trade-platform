package com.campus.trade.bean.utils;

import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * 轻量 SETNX 锁 ——【可降级】的锁。
 * 唯一使用方是 CacheUtils 的缓存重建互斥：它只是「防重复劳动」的性能锁，不承载任何业务正确性。
 * 所以 Redis 故障时 tryLock 返回 null（等同没抢到），CacheUtils 自然退化为「直接查库、不写缓存」，
 * 最坏是 DB 压力变大，绝不会算错数据。
 * 对照：交易锁走 Redisson + TradeLockTemplate，是【不降级】的（下单链路 Redis 是硬依赖）——
 * 一个降级一个不降级，区别必须说清，见文档 5.7。
 */
@Component
public class RedisLockUtils {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private SafeRedis safeRedis;

    // 释放锁脚本：仅当锁的值等于自己持有的值时才删除，避免误删他人锁（Lua 保证原子）
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    // 尝试加锁：成功返回持有凭证；失败或 Redis 故障都返回 null（一律按「没抢到」保守处理）
    public String tryLock(String key, Duration ttl) {
        String value = UUID.randomUUID().toString();
        Boolean success = safeRedis.setIfAbsentOrNull(key, value, ttl);
        return Boolean.TRUE.equals(success) ? value : null;
    }

    // 释放锁：失败只告警。锁本身带 TTL 会自行过期，这里抛出会盖住调用方真正的业务异常
    public void unlock(String key, String value) {
        if (value == null) {
            return;
        }
        safeRedis.swallow(() ->
                        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), value),
                "EVAL-UNLOCK", key);
    }
}