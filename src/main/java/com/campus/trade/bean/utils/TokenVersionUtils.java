package com.campus.trade.bean.utils;

import com.campus.trade.bean.entry.User;
import com.campus.trade.mapper.UserMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class TokenVersionUtils {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private UserMapper userMapper;

    // 版本号变更不频繁，用长 TTL 兜底；改密时会主动刷新，避免读到旧值
    private static final Duration CACHE_TTL = Duration.ofDays(7);

    // 兜底版本号：历史数据 token_version 为空时按 1 处理
    private static final int DEFAULT_VERSION = 1;

    // 查询用户当前 Token 版本号：Redis 缓存优先，未命中/故障时回源 DB 并回填缓存
    public int getCurrentVersion(Long userId) {
        String key = RedisContent.User_Token_Version_KEY + userId;
        String cached = redisGet(key);
        if (cached != null) {
            return parseVersion(cached);
        }
        User user = userMapper.selectById(userId);
        int version = (user != null && user.getTokenVersion() != null)
                ? user.getTokenVersion() : DEFAULT_VERSION;
        redisSet(key, String.valueOf(version));
        return version;
    }

    // 改密后把缓存刷新为最新版本（DB 为源，这里同步缓存）
    public void refresh(Long userId, int version) {
        redisSet(RedisContent.User_Token_Version_KEY + userId, String.valueOf(version));
    }

    private int parseVersion(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return DEFAULT_VERSION;
        }
    }

    // ---------- Redis 安全包装：故障时降级为回源查库 ----------
    private String redisGet(String key) {
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis 读取用户 Token 版本失败，降级查库 key={}", key, e);
            return null;
        }
    }

    private void redisSet(String key, String value) {
        try {
            stringRedisTemplate.opsForValue().set(key, value, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis 写入用户 Token 版本失败，忽略 key={}", key, e);
        }
    }
}