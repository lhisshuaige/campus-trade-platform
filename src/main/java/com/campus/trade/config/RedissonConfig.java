package com.campus.trade.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端。
 * 注意 database 必须读 spring.data.redis.database（本项目是 3），
 * 否则 Redisson 的锁落在 db0、缓存和黑名单在 db3，redis-cli 排查时对不上。
 *
 * 本项目两套 Redis 客户端并存是有意为之：
 *   Lettuce(StringRedisTemplate) → 缓存、Token 黑名单、轻量 SETNX 互斥（CacheUtils 的跨线程释放）
 *   Redisson(RLock)              → 交易临界区（需要看门狗续期 + 持有者校验 + 可重入）
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host}")
    private String host;
    @Value("${spring.data.redis.port}")
    private int port;
    @Value("${spring.data.redis.database:0}")
    private int database;
    @Value("${spring.data.redis.password:}")
    private String password;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(10);
        if (password != null && !password.isBlank()) {
            config.useSingleServer().setPassword(password);
        }
        return Redisson.create(config);
    }
}