package com.campus.trade.bean.utils;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
@Component
public class CacheUtils {

    @Resource
    private SafeRedis safeRedis;
    @Resource
    private RedisLockUtils redisLockUtils;

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    // 重建线程数：4。再大也不会更快 —— 底下是 Lettuce 那一条共享连接，多线程只是把同一条队排得更长
    private static final int REBUILD_THREADS = 4;
    // 重建队列容量：宁可拒接新任务，也不让任务无界堆积（见下面 REBUILD_EXECUTOR 的注释）
    private static final int REBUILD_QUEUE_CAPACITY = 200;

    /**
     * 逻辑过期场景下用于异步重建缓存的线程池（守护线程，随 JVM 退出）。
     * <p>
     * 为什么不用 {@code Executors.newFixedThreadPool(4)}：它背后是**无界** LinkedBlockingQueue，
     * 而重建速度由 Redis/DB 决定、提交速度由流量决定——热点 key 刚过期 + 后端变慢的那一段，
     * 堆积是注定的，而一个能无限长的队列只是把「后端慢」放大成「OOM」。
     * <p>
     * 为什么这个任务**可以丢**：重建失败只是下次请求再试一次，业务侧当时已经拿到旧值了，
     * 所以「有界 + 拒接」是正确的，而不是丢那一条请求就出错。
     * <p>
     * 为什么不是 {@code DiscardPolicy}：任务是**抢到分布式锁之后**才提交的，
     * 静默丢掉意味着 finally 里的 unlock 根本不跑，这把锁要白占到 10s TTL 自然过期，
     * 期间全部请求都拿不到重建权——等于用一个 OOM 面换一个「无人重建」窗口。
     * 所以用 AbortPolicy 抛异常，由提交处接住并**立即归还锁**（见 getOrLoad）
     */
    private static final ThreadPoolExecutor REBUILD_EXECUTOR = new ThreadPoolExecutor(
            REBUILD_THREADS, REBUILD_THREADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(REBUILD_QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "cache-rebuild-" + THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    // 延迟双删调度线程池（守护线程，只负责"稍后再补删一次"）
    // 直接 new 实现类而不是 Executors.newScheduledThreadPool：只有实现类暴露 getQueue()，
    // 而下面那个队列护栏需要它（ScheduledExecutorService 接口上没有队列可查，多一次强转只是把同一件事写难看）
    private static final ScheduledThreadPoolExecutor EVICT_SCHEDULER = new ScheduledThreadPoolExecutor(
            2, r -> {
                Thread t = new Thread(r, "cache-evict-" + THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    /**
     * 补删队列的护栏。调度池用的是 {@code DelayedWorkQueue}（无界），但**不能**照上面那样改成有界：
     * 优先级队列的「满」没有意义——拒接最早要执行的那一条反而错得更多，
     * 所以这里用「提交前看队列长度」代替容量限制。被跳过的那次补删只是少删一次，
     * 脏值最长活到逻辑过期就自己好了，不会写坏数据
     */
    private static final int EVICT_QUEUE_GUARD = 2000;

    // 延迟双删的等待时间：需大于"一次回源查库 + 回填缓存"的最坏耗时
    private static final long DELAYED_EVICT_MILLIS = 500L;

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
                // 队列满时 submit 直接抛 RejectedExecutionException：必须在**这里**接住并把锁还回去，
                // 否则锁会一直占到 TTL 自然过期，那 10 秒里谁都无法重建（旧值照旧返回，但新值永远上不来）
                try {
                    REBUILD_EXECUTOR.submit(() -> {
                        try {
                            rebuild(key, logicalTtl, loader);
                        } catch (Exception e) {
                            log.error("缓存异步重建失败 key={}", key, e);
                        } finally {
                            redisLockUtils.unlock(lockKey, lockValue);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    log.warn("重建队列已满({})，本次不重建，锁已归还 key={}", REBUILD_QUEUE_CAPACITY, key);
                    redisLockUtils.unlock(lockKey, lockValue);
                }
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
        safeRedis.deleteSwallow(key);
    }

    /**
     * 写库之后失效缓存：事务提交后删除 + 延迟双删。
     * <p>
     * 为什么不能在事务里直接 evict：先删缓存、后提交事务之间存在间隙，
     * 并发读会查到"尚未更新的旧库数据"并把它回填进缓存，事务提交后缓存依旧是脏的。
     * <p>
     * 策略：
     * 1) 有事务上下文：注册 afterCommit 回调，等 DB 对外可见再删；事务回滚则不删（数据没变，缓存仍有效）
     * 2) 无事务上下文（单条 auto-commit 写）：直接删
     * 3) 两种情况都再延迟补删一次，兜住"删除瞬间仍在飞行中的旧读请求"回填的旧值；
     *    补删若命中已被刷新的好数据，也只是多一次回源，不影响正确性
     */
    public void evictAfterCommit(String key) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doEvictWithDelay(key);
                }
            });
        } else {
            doEvictWithDelay(key);
        }
    }

    private void doEvictWithDelay(String key) {
        evict(key);
        // 主删已经做了，补删只是兜住「删除瞬间仍在飞行中的旧读请求」回填的旧值，
        // 所以队列反常时选择跳过而不是排队（见 EVICT_QUEUE_GUARD）
        if (EVICT_SCHEDULER.getQueue().size() >= EVICT_QUEUE_GUARD) {
            log.warn("补删队列已满({})，跳过本次延迟补删 key={}", EVICT_QUEUE_GUARD, key);
            return;
        }
        EVICT_SCHEDULER.schedule(() -> evict(key), DELAYED_EVICT_MILLIS, TimeUnit.MILLISECONDS);
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
        safeRedis.setSwallow(key, wrapper.toString(), physicalTtlWithJitter());
    }

    // 读缓存：解析包装结构并计算是否逻辑过期；脏数据解析失败则删除后当作未命中（自愈）
    private CacheEntry readCache(String key) {
        String cached = safeRedis.getOrNull(key);
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
            safeRedis.deleteSwallow(key);
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

    // 缓存包装条目：逻辑过期时间 + 是否已过期 + 业务数据 JSON
    private static final class CacheEntry {
        long expireTime;
        boolean expired;
        String dataJson;
    }
}
