package com.campus.trade.bean.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内令牌桶：**只在 Redis 判不出来的时候**当兜底闸用，不是主防线。
 *
 * 为什么需要它：限流的主实现依赖 Redis，而 Redis 一异常/熔断，{@code SafeRedis} 就返回 null（=不知道），
 * 老口径是 fail-open 放行 —— 于是「Redis 挂掉」与「限流关掉」变成同一件事，脚本偏偏挑这种时候打。
 * 这一层不读任何中间件，纯进程内计算，正好补上文档里承诺过的那句「多一层独立于 Redis 的防护」。
 *
 * 为什么它不能当主防线（必须说清的边界）：额度是按**单个进程**算的，多实例部署时总额度会被放大 N 倍，
 * 而且重启即清零。所以定位是「故障期的保守闸门」：Redis 正常时一律走 Redis 的滑动窗口，本类一次都不被调用。
 *
 * 为什么用令牌桶而不是照搬 Redis 那套滑动窗口日志：兜底路径只要求「同一来源的循环调用会被掐住」，
 * 不要求窗口边界精确；而滑动窗口要在本地维护一个时间戳列表并自己清理，代价和它的收益不成比例。
 */
@Slf4j
@Component
public class LocalRateLimiter {

    /**
     * 桶数量的硬上限。key 里含来源 IP，公网扫描能在几分钟内造出几十万个不同的 IP ——
     * 不主动淘汰，这个 Map 自己就变成第二个 DoS 面（内存换不进 GC 也帮不上）。
     * 超限时做一次空闲桶清理，而不是拒绝建新桶：后者会让攻击者用伪造 IP 直接把所有人的兜底闸撑到「谁都不限」。
     */
    private static final int MAX_BUCKETS = 20000;

    //key = 与 Redis 侧完全同一个 key：这样即使两边都活着，语义也是「同一个来源」，不会各算一套
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 取一个令牌。
     *
     * @param key             限流标识（与 Redis 侧同 key）
     * @param capacity        桶容量 = 窗口内允许的最大次数，允许一次性突发这么多
     * @param refillPerSecond 每秒回填多少令牌 = maxCount / windowSeconds
     * @param idleTtlNanos    空闲多久后这个桶可以丢掉（至少要能装满一次，否则等于提前放掉额度）
     * @return true = 放行；false = 本地兜底额度用尽
     */
    public boolean tryAcquire(String key, int capacity, double refillPerSecond, long idleTtlNanos) {
        long now = System.nanoTime();
        if (buckets.size() > MAX_BUCKETS) {
            evictIdle(now, idleTtlNanos);
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        return bucket.tryAcquire(capacity, refillPerSecond, now);
    }

    //丢掉「已经空闲到早就装满了」的桶：这种桶留着不改变任何判定结果，只是占内存
    private void evictIdle(long now, long idleTtlNanos) {
        int before = buckets.size();
        buckets.entrySet().removeIf(entry -> now - entry.getValue().lastRefillNanos > idleTtlNanos);
        log.warn("[限流] 本地桶数量 {} 超过上限，已清理空闲桶，剩余 {} 个", before, buckets.size());
    }

    /**
     * 一个桶。字段全部由 {@code synchronized} 保护：容量、回填速率这些参数来自注解，同一 key 恒定，
     * 所以不需要 CAS 结构，一个方法级锁足够（这条路径只在 Redis 故障期被走到，不是热路径）。
     */
    private static final class Bucket {
        private double tokens;
        //同时也是「最后访问时间」：每次补充令牌都会推进，evictIdle 依它判断空闲
        private long lastRefillNanos;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastRefillNanos = now;
        }

        synchronized boolean tryAcquire(int capacity, double refillPerSecond, long now) {
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000d;
            //只在全新桶（tokens 已在构造时装满）之外才判断，所以这里不能写成 tokens > capacity
            if (elapsedSeconds > 0 && refillPerSecond > 0) {
                tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
                lastRefillNanos = now;
            }
            if (tokens < 1d) {
                return false;
            }
            tokens -= 1d;
            return true;
        }
    }
}
