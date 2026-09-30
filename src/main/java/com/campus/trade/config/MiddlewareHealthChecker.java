package com.campus.trade.config;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动自检：把"当前 Redis 是否可用、哪些能力处于降级状态"在启动日志里说清楚。
 * 刻意【不抛异常】—— 软依赖不可用不该阻止应用启动（本地演示时 Redis 经常没开）。
 * 需要"问题在部署期暴露"的严格语义时，把 app.redis.required 设为 true：探测失败直接拒绝启动，
 * 比上线后满屏 503 更容易被发现。
 */
@Slf4j
@Component
public class MiddlewareHealthChecker implements ApplicationRunner {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${spring.data.redis.host}")
    private String host;
    @Value("${spring.data.redis.port}")
    private int port;
    @Value("${app.redis.required:false}")
    private boolean redisRequired;

    @Resource
    private RedissonClientHolder redissonClientHolder;

    @Override
    public void run(ApplicationArguments args) {
        boolean redisUsable = true;
        try {
            stringRedisTemplate.execute((org.springframework.data.redis.core.RedisCallback<String>) connection ->
                    connection.ping());
            log.info("Redis(Lettuce) 可用 {}:{} —— 缓存/限流/黑名单全部正常", host, port);
        } catch (Exception e) {
            redisUsable = false;
            //“限流放行”这句已经不准了：@RateLimit 从 P2-11 起有本地兜底桶，Redis 断了照样拦；
            //真正还是放行的只有【当日额度】那一类计数（INCR 拿不到值），两者必须分开报
            log.warn("Redis(Lettuce) 不可用 {}:{}，已进入降级模式：缓存直查DB、@RateLimit 改走本地兜底桶、"
                            + "当日额度计数放行、黑名单按 fail-open 配置处理。原因：{}",
                    host, port, e.getMessage());
        }
        // 交易锁能力单独探一次（warmUp 只建客户端、不改熔断状态）
        try {
            if (redissonClientHolder.warmUp() != null) {
                log.info("Redis(Redisson) 可用 —— 交易分布式锁正常");
            } else {
                log.warn("Redis(Redisson) 暂不可用 —— 下单返回 503；后台探测任务会自动重建，Redis 恢复后【无需重启应用】");
            }
        } catch (Exception e) {
            log.warn("Redis(Redisson) 预热异常（不影响启动）：{}", e.getMessage());
        }
        if (!redisUsable && redisRequired) {
            throw new IllegalStateException("Redis 不可用且 app.redis.required=true，拒绝启动", null);
        }
    }
}