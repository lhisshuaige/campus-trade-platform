package com.campus.trade.bean.utils;

import cn.hutool.crypto.SecureUtil;
import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;

@Component
public class TokenBlacklistUtils {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private JwtUtils jwtUtils;

    // 将 token 加入黑名单，过期时间与 token 剩余有效期一致，到期后自动清除
    public void add(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        long ttlMillis;
        try {
            Claims claims = jwtUtils.parseToken(token);
            Date expiration = claims.getExpiration();
            ttlMillis = expiration.getTime() - System.currentTimeMillis();
        } catch (Exception e) {
            // token 已过期或非法，无需加入黑名单
            return;
        }
        if (ttlMillis <= 0) {
            return;
        }
        String key = RedisContent.Token_Blacklist_KEY + tokenKey(token);
        stringRedisTemplate.opsForValue().set(key, "1", Duration.ofMillis(ttlMillis));
    }

    // 判断 token 是否已在黑名单中（即已登出）
    public boolean contains(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(
                stringRedisTemplate.hasKey(RedisContent.Token_Blacklist_KEY + tokenKey(token)));
    }

    // 对 token 做 MD5，得到固定 32 位的短 key，避免 Redis 里存超长 token
    private String tokenKey(String token) {
        return SecureUtil.md5(token);
    }
}