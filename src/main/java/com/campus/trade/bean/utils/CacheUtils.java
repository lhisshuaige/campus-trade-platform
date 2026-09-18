package com.campus.trade.bean.utils;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
@Component
public class CacheUtils {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisLockUtils redisLockUtils;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    // 逻辑过期场景下用于异步重建缓存的线程池（守护线程，随 JVM 退出）
    private static final ExecutorService REBUILD_EXECUTOR = Executors.newFixedThreadPool(
            4, r -> {
                Thread t = new Thread(r, "cache-rebuild-" + THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    // 冷启动抢锁重建的锁 TTL
    private static final Duration LOCK_TTL = Duration.ofSeconds(10);
    // 空值缓存（防穿透）的逻辑过期时间：很短，防止永久缓存脏空数据
    private static final Duration NULL_LOGICAL_TTL = Duration.ofSeconds(30);
    // 物理过期兜底：防止逻辑过期后长期无人访问导致内存不释放
    private static final Duration PHYSICAL_TTL = Duration.ofHours(24);
    // 物理 TTL 随机抖动上限(秒)，防雪崩
    private static final int PHYSICAL_JITTER_SECONDS = 600;
    // 锁 key 统一前缀，与业务 key 隔离
    private static final String LOCK_PREFIX = "lock:cache:";

    /**
     * 逻辑过期方式解决缓存击穿（配合异步重建）+ 防穿透
     *
     * @param key          redis 缓存 key
     * @param logicalTtl   逻辑过期时间
     * @param deserializer 字符串转对象的反序列化方法
     * @param loader       数据源加载方法（查 DB）
     * @return 业务对象
     */
    public <T> T getOrLoad(String key, Duration logicalTtl,
                           Function<String, T> deserializer,
                           Supplier<T> loader) {
        // 1. 查缓存
        CacheEntry entry = readCache(key);

        // 1.1 命中且未逻辑过期：直接返回，全程无锁
        if (entry != null && !entry.expired) {
            return deserialize(entry, deserializer);
        }

        // 1.2 命中但已逻辑过期：抢锁的线程异步重建，所有线程立即返回旧值（不阻塞、不击穿）
        if (entry != null) {
            String lockKey = LOCK_PREFIX + key;
            String lockValue = redisLockUtils.tryLock(lockKey, LOCK_TTL);
            if (lockValue != null) {
                REBUILD_EXECUTOR.submit(() -> {
                    try {
                        rebuild(key, logicalTtl, loader);
                    } catch (Exception e) {
                        log.error("缓存异步重建失败 key={}", key, e);
                    } finally {
                        redisLockUtils.unlock(lockKey, lockValue);
                    }
                });
            }
            return deserialize(entry, deserializer);
        }

        // 2. 缓存不存在（冷启动）：抢锁同步加载，避免首屏并发打穿
        String lockKey = LOCK_PREFIX + key;
        String lockValue = redisLockUtils.tryLock(lockKey, LOCK_TTL);
        if (lockValue == null) {
            // 没抢到锁：直接查库兜底，不写缓存
            return loader.get();
        }
        try {
            // 双重检查：可能已被其它线程重建好了
            CacheEntry again = readCache(key);
            if (again != null && !again.expired) {
                return deserialize(again, deserializer);
            }
            T value = loader.get();
            writeCache(key, logicalTtl, value);
            return value;
        } finally {
            redisLockUtils.unlock(lockKey, lockValue);
        }
    }

    /**
     * 便捷重载：反序列化目标为具体类型
     */
    public <T> T getOrLoad(String key, Duration logicalTtl, Class<T> clazz, Supplier<T> loader) {
        return getOrLoad(key, logicalTtl, json -> JSONUtil.toBean(json, clazz), loader);
    }

    /**
     * 删除缓存，供业务增删改后调用，统一 key 管理入口
     */
    public void evict(String key) {
        redisDelete(key);
    }

    // 异步重建：再查一次，避免重复重建
    private <T> void rebuild(String key, Duration logicalTtl, Supplier<T> loader) {
        CacheEntry entry = readCache(key);
        if (entry != null && !entry.expired) {
            return;
        }
        writeCache(key, logicalTtl, loader.get());
    }

    // 写缓存：把「业务数据 + 逻辑过期时间」整体以 JSON 存入，并挂物理兜底 TTL
    private void writeCache(String key, Duration logicalTtl, Object value) {
        Duration ttl = value == null ? NULL_LOGICAL_TTL : logicalTtl;
        JSONObject wrapper = new JSONObject();
        wrapper.set("expireTime", System.currentTimeMillis() + ttl.toMillis());
        // data 直接内嵌为 JSON 节点，null 表示缓存的是空值（防穿透）
        wrapper.set("data", value == null ? null : JSONUtil.parse(JSONUtil.toJsonStr(value)));
        redisSet(key, wrapper.toString(), physicalTtlWithJitter());
    }

    // 读缓存：解析包装结构并计算是否逻辑过期；脏数据解析失败则删除后当作未命中（自愈）
    private CacheEntry readCache(String key) {
        String cached = redisGet(key);
        if (cached == null || cached.isBlank()) {
            return null;
        }
        try {
            JSONObject wrapper = JSONUtil.parseObj(cached);
            Object dataNode = wrapper.get("data");
            CacheEntry entry = new CacheEntry();
            entry.expireTime = wrapper.getLong("expireTime", 0L);
            entry.expired = System.currentTimeMillis() > entry.expireTime;
            entry.dataJson = dataNode == null ? null : JSONUtil.toJsonStr(dataNode);
            return entry;
        } catch (Exception e) {
            log.warn("缓存解析失败，删除脏 key={}", key, e);
            redisDelete(key);
            return null;
        }
    }

    private <T> T deserialize(CacheEntry entry, Function<String, T> deserializer) {
        // dataJson 为 null 表示缓存的是空值，直接返回 null，防穿透
        return entry.dataJson == null ? null : deserializer.apply(entry.dataJson);
    }

    // 物理 TTL 加抖动，避免大量 key 同一时刻物理失效
    private Duration physicalTtlWithJitter() {
        return PHYSICAL_TTL.plusSeconds(ThreadLocalRandom.current().nextLong(PHYSICAL_JITTER_SECONDS));
    }

    // ---------- Redis 安全包装：故障时降级，不阻断回源查库 ----------
    private String redisGet(String key) {
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis 读取失败，降级 key={}", key, e);
            return null;
        }
    }

    private void redisSet(String key, String value, Duration ttl) {
        try {
            stringRedisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.warn("Redis 写入失败，忽略 key={}", key, e);
        }
    }

    private void redisDelete(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis 删除失败，忽略 key={}", key, e);
        }
    }

    // 缓存包装条目：逻辑过期时间 + 是否已过期 + 业务数据 JSON
    private static final class CacheEntry {
        long expireTime;
        boolean expired;
        String dataJson;
    }
}
