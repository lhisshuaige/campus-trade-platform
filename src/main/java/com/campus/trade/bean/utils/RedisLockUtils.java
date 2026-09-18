package com.campus.trade.bean.utils;

import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class RedisLockUtils {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 释放锁脚本：仅当锁的值等于自己持有的值时才删除，避免误删他人锁（Lua 保证原子）
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    // 尝试加锁：成功返回持有凭证，失败返回 null
    public String tryLock(String key, Duration ttl) {
        String value = UUID.randomUUID().toString();
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, value, ttl.toMillis(), TimeUnit.MILLISECONDS);
        return Boolean.TRUE.equals(success) ? value : null;
    }

    // 释放锁
    public void unlock(String key, String value) {
        if (value == null) {
            return;
        }
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), value);
    }
}