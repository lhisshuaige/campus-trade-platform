package com.campus.trade.bean.utils;

import cn.hutool.crypto.SecureUtil;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;

@Slf4j
@Component
public class TokenBlacklistUtils {

    @Resource
    private SafeRedis safeRedis;

    @Resource
    private JwtUtils jwtUtils;

    // Redis 故障、黑名单读不出来时的方向：true=放行(默认，保可用) / false=拒绝(保安全)
    // 放行的代价：已登出的 token 在故障窗口内还能用；但它仍受签名校验 + token_version + 24h 过期三重约束
    // 拒绝的代价：Redis 一挂全站所有需登录接口立刻不可用 —— 用可用性换安全，默认不划算，故做成开关
    @Value("${app.auth.blacklist-fail-open:true}")
    private boolean blacklistFailOpen;

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
        // 登出本身不能因 Redis 故障返回 500（用户会觉得"退不出去"），故 best-effort + 明确 error 日志便于告警
        // 若更偏安全，可改用 required 语义让登出直接 503 提示重试（登出 QPS 极低，代价可接受）
        if (!safeRedis.setSwallow(key, "1", Duration.ofMillis(ttlMillis))) {
            // 只有真没写进去才告警：无条件的 error 会让日志全假警报，告警也就废了
            log.error("Token 黑名单写入降级，该 token 在剩余有效期内仍可能可用 key={} 剩余ms={}", key, ttlMillis);
        }
    }

    // 判断 token 是否已在黑名单中（即已登出）
    public boolean contains(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String key = RedisContent.Token_Blacklist_KEY + tokenKey(token);
        Boolean blacklisted = safeRedis.hasKeyOrNull(key);
        if (blacklisted == null) {
            // Redis 不可用 = 「不知道是否已登出」，绝不能让它冒泡成 500
            if (blacklistFailOpen) {
                return false;
            }
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "登录校验暂不可用，请稍后重试");
        }
        return blacklisted;
    }

    // 对 token 做 MD5，得到固定 32 位的短 key，避免 Redis 里存超长 token
    private String tokenKey(String token) {
        return SecureUtil.md5(token);
    }
}