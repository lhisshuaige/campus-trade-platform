package com.campus.trade.bean.utils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
@Component
public class UserActionLimitUtils {

    @Resource
    private SafeRedis safeRedis;

    // 计数 +1 并判断是否仍在当日上限内（INCR 原子操作，天然并发安全）
    public boolean tryAcquire(Long userId, int maxPerDay) {
        // key 带日期，天然按天重置；再加 TTL 到当天 24 点，让旧 key 自动清理
        String key = RedisContent.User_Loginout_Count_KEY + userId + ":" + LocalDate.now();
        Long count = safeRedis.incrOrNull(key);
        if (count == null) {
            // Redis 故障：限流是【保护措施】不是【业务功能】，fail-open 放行。
            // 若在此拒绝，Redis 一挂就没人能登录 —— 不能因为保险丝坏了就把整栋楼断电。
            // 代价：故障窗口内失去防爆破计数；兜底靠 BCrypt 成本因子、token_version 与后续网关层限流。
            log.warn("登录/登出限流不可用，本次放行 userId={}", userId);
            return true;
        }
        if (count == 1L) {
            // 首次计数，设置到当天 24 点自动过期；设失败也只是多留一个 key，不影响判定
            safeRedis.expireSwallow(key, ttlUntilEndOfDay());
        }
        return count <= maxPerDay;
    }

    // 距离当天 24 点还有多久
    private Duration ttlUntilEndOfDay() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return Duration.between(now, nextMidnight);
    }

    // ---------- 登录连续失败锁定（与上面的「每日总量」是两个维度） ----------

    /**
     * 是否处于锁定期。
     * 必须在密码校验之前调：否则计数挂在校验之后，失败的尝试根本不计数，上限对撞库无效。
     * Redis 不可用（getOrNull 返回 null）时判「未锁定」放行，与每日限流同一条 fail-open 纪律：
     * 保险丝坏了不能把整栋楼断电。代价 = 故障窗口内失去防撞库，兼底靠 BCrypt 成本因子与网关层限流。
     */
    public boolean isLoginLocked(Long userId, int maxFail) {
        return readFailCount(RedisContent.User_Login_Fail_KEY + userId) >= maxFail;
    }

    /**
     * 记一次登录失败，返回累计次数（Redis 不可用返回 -1，调用方按「不锁定」处理）。
     */
    public long recordLoginFailure(Long userId, int lockMinutes) {
        String key = RedisContent.User_Login_Fail_KEY + userId;
        Long count = safeRedis.incrOrNull(key);
        if (count == null) {
            log.warn("登录失败计数不可用，本次不做锁定 userId={}", userId);
            return -1L;
        }
        if (count == 1L) {
            //固定窗口：从第一次失败起算 lockMinutes 后自动过期解锁。
            //故意不续期 —— 每次失败都重置 TTL 等于把窗口续到爆破结束，账号就再也解不开
            safeRedis.expireSwallow(key, Duration.ofMinutes(lockMinutes));
        }
        return count;
    }

    /** 登录成功后清零，不影响下一次正常登录 */
    public void clearLoginFailure(Long userId) {
        safeRedis.deleteSwallow(RedisContent.User_Login_Fail_KEY + userId);
    }

    // 读失败次数：无记录 / 脏数据 / Redis 不可用都归为 0（= 不锁，fail-open）
    private long readFailCount(String key) {
        String value = safeRedis.getOrNull(key);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            log.warn("登录失败计数脏数据，归零 key={}", key);
            return 0L;
        }
    }
}
