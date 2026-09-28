package com.campus.trade.config;

import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.utils.RedisCircuitBreaker;
import com.campus.trade.bean.utils.RedisContent;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Redisson 客户端持有者：自己管客户端的「创建 / 冷却重试 / 退避 / 恢复探测 / 关闭」。
 *
 * 为什么不用 @Bean @Lazy RedissonClient + ObjectProvider（上一版的做法，实测不成立）：
 * 1) 它把「能否自愈」押在 Spring 的一个实现细节上（创建失败的 singleton 不被缓存、下次 getBean 会重建）。
 *    真实表现是：Redis 起回来之后下单仍然持续 503，必须重启应用 —— 隐式行为不可依赖。
 * 2) 失败的 Redisson.create() 会把它已经拉起来的 Netty EventLoopGroup 留在外面，
 *    靠用户请求反复触发重建 = 每失败一次漏一批线程，越试越糟。
 * 3) 没有恢复探测：只能拿用户的下单请求当探针，Redis 恢复后的「第一枪」依然可能是失败的那一次。
 *
 * 现在的语义：
 * - 客户端只建一次并常驻；建失败进入冷却（指数退避 5s→10s→…→60s 上限），冷却期内快速失败，不吃连接超时；
 * - 后台探测在 Redis 恢复后【先把客户端建好】，用户请求永远不承担重建成本；
 * - 客户端一旦建好，Redis 之后的抖动/重启由 Redisson 内部自动重连（connectTimeout/timeout/retryAttempts 已配），本类不再干预。
 *
 * 两套 Redis 客户端并存是有意为之：
 *   Lettuce(StringRedisTemplate) → 缓存、Token 黑名单、轻量 SETNX 互斥（CacheUtils 的跨线程释放）
 *   Redisson(RLock)              → 交易临界区（需要看门狗续期 + 持有者校验 + 可重入）
 * database 必须读 spring.data.redis.database（本项目是 3），否则锁落 db0、缓存落 db3，redis-cli 排查对不上。
 */
@Slf4j
@Component
public class RedissonClientHolder {

    @Value("${spring.data.redis.host}")
    private String host;
    @Value("${spring.data.redis.port}")
    private int port;
    @Value("${spring.data.redis.database:0}")
    private int database;
    @Value("${spring.data.redis.password:}")
    private String password;

    // 黑洞型故障（IP 不通 / 防火墙丢包）下超时必须小：默认 10s×3 次会把一次重建拖成 30 秒以上，
    // 那不叫降级，叫 fail-slow —— 线程池会先被挂死的请求打光
    private static final int CONNECT_TIMEOUT_MS = 1000;
    private static final int COMMAND_TIMEOUT_MS = 1000;
    private static final int RETRY_ATTEMPTS = 1;
    private static final int RETRY_INTERVAL_MS = 200;

    // 创建失败后的冷却与退避：长时间故障下不会每 5 秒漏一批 Netty 线程
    private static final long CREATE_COOLDOWN_MIN_MS = 5000L;
    private static final long CREATE_COOLDOWN_MAX_MS = 60_000L;

    private final AtomicReference<RedissonClient> clientRef = new AtomicReference<>();

    // 重建节流：失败的 Redisson.create() 会泄漏自己拉起的 Netty 线程，所以重建频率必须指数退避压住
    private final AtomicLong nextCreateAtMillis = new AtomicLong(0L);
    private final AtomicLong createCooldownMillis = new AtomicLong(CREATE_COOLDOWN_MIN_MS);

    @Resource
    private RedisCircuitBreaker circuitBreaker;

    /**
     * 请求路径专用：【绝对不建连】。
     * 上一版让下单请求自己去触发重建，结果就是"用户请求当探针 + 和后台探测抢同一个冷却窗口"，
     * 观测上时快时慢，看着像冷却没生效。现在建连只由 probe()/warmUp() 做，请求要么拿到客户端，要么 0ms 拿 503。
     */
    public RedissonClient current() {
        if (circuitBreaker.isTripped()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "系统繁忙，请稍后重试");
        }
        RedissonClient client = clientRef.get();
        if (client == null || client.isShutdown()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "系统繁忙，请稍后重试");
        }
        return client;
    }

    /** 客户端是否就绪（不触发建连） */
    public boolean available() {
        RedissonClient client = clientRef.get();
        return client != null && !client.isShutdown();
    }

    /** 唯一会创建客户端的入口，且只在"没有可用客户端"时干活 */
    private synchronized RedissonClient ensureClient() {
        RedissonClient exist = clientRef.get();
        if (exist != null && !exist.isShutdown()) {
            return exist;
        }
        long now = System.currentTimeMillis();
        if (now < nextCreateAtMillis.get()) {
            return null;   // 还在重建节流期内，交给下一轮探测
        }
        long cooldown = createCooldownMillis.get();
        try {
            RedissonClient created = Redisson.create(buildConfig());
            clientRef.set(created);
            nextCreateAtMillis.set(0L);
            createCooldownMillis.set(CREATE_COOLDOWN_MIN_MS);
            log.info("Redisson 客户端已就绪 {}:{} db={}，交易锁能力可用", host, port, database);
            return created;
        } catch (Exception e) {
            // 节流窗口从【失败这一刻】起算 —— 上一版用的是"尝试开始时刻"，
            // 建连本身吃掉的 1.2s 会把窗口悄悄缩短，这也是"冷却看起来不准"的一个原因
            nextCreateAtMillis.set(System.currentTimeMillis() + cooldown);
            createCooldownMillis.set(Math.min(cooldown * 2, CREATE_COOLDOWN_MAX_MS));
            log.warn("Redisson 客户端创建失败，{}ms 后再试：{}", createCooldownMillis.get(), e.getMessage());
            return null;
        }
    }

    /**
     * 后台探测：稳态下每轮一条 O(1) 命令；故障期间负责把客户端建好、把熔断闭合。
     * 它同时充当熔断的"半开试探者"，所以【不受 shouldSkip 限制】——否则开路后永远没人去验证。
     */
    @Scheduled(fixedDelayString = "${app.trade.lock-probe-interval-ms:5000}",
            initialDelayString = "${app.trade.lock-probe-interval-ms:5000}")
    public void probe() {
        RedissonClient client = ensureClient();
        if (client == null) {
            return;
        }
        try {
            // 真实命令验证：能同时发现"客户端在但 Redis 已换/已挂"这种 isShutdown 看不出来的状态
            client.getBucket(RedisContent.Redis_Health_KEY).isExists();
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            circuitBreaker.recordFailure("redisson-probe");
            // 客户端本身坏了（极罕见，Redisson 一般能自愈）：扔掉它，下一轮重建
            closeQuietly(clientRef.getAndSet(null));
            log.debug("Redisson 探测失败：{}", e.getMessage());
        }
    }

    /** 启动时预热一次：让启动日志能直接说清"下单现在能不能用" */
    public RedissonClient warmUp() {
        RedissonClient client = ensureClient();
        if (client == null && available()) {
            return clientRef.get();
        }
        return client;
    }

    private Config buildConfig() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(10)
                .setConnectTimeout(CONNECT_TIMEOUT_MS)
                .setTimeout(COMMAND_TIMEOUT_MS)
                .setRetryAttempts(RETRY_ATTEMPTS)
                .setRetryInterval(RETRY_INTERVAL_MS);
        if (password != null && !password.isBlank()) {
            config.useSingleServer().setPassword(password);
        }
        return config;
    }

    @PreDestroy
    public void destroy() {
        closeQuietly(clientRef.getAndSet(null));
    }

    private void closeQuietly(RedissonClient client) {
        if (client == null) {
            return;
        }
        try {
            client.shutdown();
        } catch (Exception e) {
            log.warn("Redisson 客户端关闭异常（忽略，应用正在收尾）", e);
        }
    }
}