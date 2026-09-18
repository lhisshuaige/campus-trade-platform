package com.campus.trade.bean.utils;

import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

//生成全局唯一id
@Component
public class RedisWorker {

    // 序列号占用的位数
    private static final int COUNT_BITS = 32;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 生成全局唯一ID：高位放时间戳(秒)，低位放当天自增序列
    public long nextId(String keyPrefix) {
        // 1. 生成时间戳(秒级)
        long timestamp = System.currentTimeMillis() / 1000;
        // 2. 生成当天内的自增序列号(key 带日期，每天从 0 重新开始)
        String date = LocalDateTime.now().format(DATE_FORMATTER);
        String key = "icr:" + keyPrefix + ":" + date;
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count == null) {
            throw new IllegalStateException("生成全局唯一ID失败");
        }
        if (count == 1L) {
            // 首次计数，设置两天后过期，避免 key 无限堆积
            stringRedisTemplate.expire(key, Duration.ofDays(2));
        }
        // 3. 拼接：时间戳左移 32 位，低 32 位放序列号
        return timestamp << COUNT_BITS | count;
    }
}