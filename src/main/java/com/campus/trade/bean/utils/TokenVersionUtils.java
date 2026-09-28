package com.campus.trade.bean.utils;

import com.campus.trade.bean.entry.User;
import com.campus.trade.mapper.UserMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

/**
 * Token 版本号 + 账号状态的读取与失效。
 * 真值永远在 user 表（token_version / status），Redis 只是把「每次请求一条 SELECT」
 * 变成「每个用户每 TTL 一条 SELECT」。
 */
@Slf4j
@Component
public class TokenVersionUtils {

    @Resource
    private SafeRedis safeRedis;

    @Resource
    private UserMapper userMapper;

    // 缓存 TTL：从 7 天压到 5 分钟。
    // 版本号本身极少变，长 TTL 没问题；但这里同时缓存了 status —— 一旦出现「没走本类失效协议」
    // 的写入（DBA 手改库、数据导入、未来新增的禁用入口），长 TTL 会让禁用迟迟不生效。
    // 5 分钟 = 最坏 5 分钟自行收敛，代价是每用户每 5 分钟一条 SELECT，本单体 QPS 下可忽略。
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    // 兜底版本号：历史数据 token_version 为空时按 1 处理
    private static final int DEFAULT_VERSION = 1;
    private static final int STATUS_NORMAL = 1;

    // 缓存值格式：version|status —— 一次 GET 拿全鉴权态，不为 status 多发一条命令
    private static final String SEPARATOR = "|";

    /** 鉴权态：Token 版本号真值 + 账号状态 */
    public record AuthState(int version, int status) {
    }

    /**
     * 查询用户当前鉴权态：Redis 缓存优先，未命中/故障时回源 DB 并回填。
     * Redis 挂了语义不变（DB 才是真值，缓存只加速）。
     */
    public AuthState getAuthState(Long userId) {
        String key = RedisContent.User_Token_Version_KEY + userId;
        String cached = safeRedis.getOrNull(key);
        // 缓存只是加速：读失败/未命中/格式不认识（升级前遗留的纯版本号值）一律当「不知道」，回源 DB
        AuthState cachedState = parse(cached);
        if (cachedState != null) {
            return cachedState;
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            // 用户不存在：返回一个永远匹配不上的鉴权态，让拦截器按「失效」处理，不给匿名放行
            return new AuthState(-1, 0);
        }
        AuthState state = new AuthState(
                user.getTokenVersion() == null ? DEFAULT_VERSION : user.getTokenVersion(),
                user.getStatus() == null ? STATUS_NORMAL : user.getStatus());
        safeRedis.setSwallow(key, state.version() + SEPARATOR + state.status(), CACHE_TTL);
        return state;
    }

    // 只要版本号（不需要状态的场景用，例如排查脚本）
    public int getCurrentVersion(Long userId) {
        return getAuthState(userId).version();
    }

    /**
     * 改密 / 禁用后失效鉴权态缓存 —— 必须挂在事务提交之后。
     * 提交前删：并发读回源拿到【旧版本/旧状态】再回填，改密与禁用都形同没生效
     * （与 CacheUtils.evictAfterCommit 同一条协议）。
     * 删除而不写回：删完下一跳必然回源 DB 取真值，不存在「把过期值写回去」的竞态，
     * 也就不需要像旧 refresh 那样在 Java 里先算出新版本号。
     */
    public void evictAfterCommit(Long userId) {
        String key = RedisContent.User_Token_Version_KEY + userId;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeRedis.deleteSwallow(key);
                }
            });
        } else {
            safeRedis.deleteSwallow(key);
        }
    }

    private AuthState parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            //注意 split 的参数是正则，写 "|" 会被当成「空分支」，必须转义
            String[] parts = value.trim().split("\\" + SEPARATOR, 2);
            if (parts.length != 2) {
                return null;
            }
            return new AuthState(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        } catch (Exception e) {
            //脏数据按未命中处理，回源自愈
            return null;
        }
    }
}