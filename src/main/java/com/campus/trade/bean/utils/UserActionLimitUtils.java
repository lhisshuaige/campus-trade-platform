package com.campus.trade.bean.utils;

import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
public class UserActionLimitUtils {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 计数 +1 并判断是否仍在当日上限内（INCR 原子操作，天然并发安全）
    public boolean tryAcquire(Long userId, int maxPerDay) {
        // key 带日期，天然按天重置；再加 TTL 到当天 24 点，让旧 key 自动清理
        String key = RedisContent.User_Loginout_Count_KEY + userId + ":" + LocalDate.now();
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count == null) {
            return false;
        }
        if (count == 1L) {
            // 首次计数，设置到当天 24 点自动过期
            stringRedisTemplate.expire(key, ttlUntilEndOfDay());
        }
        return count <= maxPerDay;
    }

    // 距离当天 24 点还有多久
    private Duration ttlUntilEndOfDay() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return Duration.between(now, nextMidnight);
    }
}
