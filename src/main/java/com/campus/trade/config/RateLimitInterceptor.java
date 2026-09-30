package com.campus.trade.config;

import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.utils.LocalRateLimiter;
import com.campus.trade.bean.utils.RateLimit;
import com.campus.trade.bean.utils.RedisContent;
import com.campus.trade.bean.utils.SafeRedis;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 接口级限流（{@code @RateLimit} 的执行方）。三层里的第一层：挡「同一来源在短时间内的循环调用」。
 *
 * 主判据在 Redis 的滑动窗口日志（一条 Lua 里做完「剔旧 → 判额度 → 记账」，全过程原子），
 * Redis 判不出来时不退化成放行，而是走 {@link LocalRateLimiter} 这个进程内令牌桶 ——
 * 这才是「限流是保险丝，但保险丝坏了也不能整栋楼断电」这句话的具体兑现方式。
 *
 * 两个刻意的定位说明：
 * 1) **做成拦截器而不是 AOP 切面**。原计划写的是 AOP，改过来的三条理由：
 *    ① 拦截器在参数绑定与 JSON 反序列化【之前】，挡脚本要挡在成本发生之前；
 *    ② 项目已有 {@code @RequireRole} + {@code RoleInterceptor} 这套「注解 + 拦截器」的先例，不另起一种风格；
 *    ③ 被限流的请求因此根本进不了 Controller，{@code @Log} 切面不触发 —— 见下面第 3) 条。
 * 2) **注册顺序在认证之后、授权之前**（order=2）。放认证之后才拿得到 {@code loginUserId}，
 *    否则所有写接口只能按 IP 计，一个机房出口就共用一份额度；放授权之前是先量后权。
 * 3) **429 不进 oper_log 表，只留一条 warn 日志**（上一条的结果）。因为「刷被限流的接口」本身
 *    就是一种把审计表刷满的方式，留痕的需求用日志文件已经满足；管理员要查拦截情况得 grep，不去查表。
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    @Resource
    private SafeRedis safeRedis;

    @Resource
    private LocalRateLimiter localRateLimiter;

    // 总开关：本地压测、给客户演示时可以一把关掉，与 content.audit-enabled / order-timeout.mq-enabled 同一风格
    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;

    // Redis 判不出来时是否走本地兜底桶。false = 回到「判不出来就放行」的老口径，除非在专门验证降级链路，不要关
    @Value("${app.rate-limit.local-fallback:true}")
    private boolean localFallback;

    // IP 维度默认用连接地址而不是 X-Forwarded-For：那是客户端可伪造的请求头，
    // 拿它当限流 key，攻击者在头里换一个值就能绕过整层限流。反代部署才需要打开（见 application-prod.yaml）
    @Value("${app.rate-limit.use-forwarded-for:false}")
    private boolean useForwardedFor;

    /**
     * 滑动窗口日志。为什么不用 INCR：固定窗口（INCR + 第一次 EXPIRE）有个必现的毛病 ——
     * 上限 10 的窗口，在上一窗口末尾打 10 次、新窗口开头再打 10 次，2 秒内实际放过 20 次。
     * ZSET 里每个成员就是一个请求的时间戳，每次先按分数把窗口外的剔掉再 ZCARD，窗口是真的在滑。
     * 成员数上限 = maxCount（超过就被拒、不再写入），所以这个 key 占的内存天然有界，不需要清理任务。
     */
    private static final RedisScript<Long> SLIDING_WINDOW = new DefaultRedisScript<>(
            """
            local now    = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local max    = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            if redis.call('ZCARD', KEYS[1]) >= max then
                return 0
            end
            redis.call('ZADD', KEYS[1], now, ARGV[4])
            -- 每次放行都续一次期：被拒时不写入也就不会续期，最后一个请求过去一个窗口后 key 自动消失
            redis.call('PEXPIRE', KEYS[1], window)
            return 1
            """, Long.class);

    // 成员唯一性用：进程内单调递增 + 进程启动时随机出来的 workerId。
    // 为什么不能用 Lua 里的 math.random：脚本要复制给副本/其他节点，非确定的随机数会让副本算出不一样的成员；
    // 为什么 workerId 不能省：多实例部署时两个进程可能在同一毫秒各自生成「1:时间戳-序号」，
    // 成员撞车会被 ZADD 当成同一次请求覆盖掉，等于悄悄少计一次
    private static final String WORKER_ID = Long.toHexString(ThreadLocalRandom.current().nextLong(1L << 48));
    private static final AtomicLong MEMBER_SEQ = new AtomicLong();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 没标注解 = 不限流。本机制是白名单式的，不给全局加默认闸
        if (!enabled || !(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        // 预检请求没有业务语义（浏览器自己发的），不该占用户的额度
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        RateLimit limit = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (limit == null) {
            return true;
        }

        String bizKey = limit.key().isBlank()
                ? handlerMethod.getBeanType().getSimpleName() + "." + handlerMethod.getMethod().getName()
                : limit.key();
        String key = RedisContent.Rate_Limit_KEY + bizKey + ":" + resolveSubject(request, limit.dimension());

        if (!acquire(key, limit)) {
            //只 warn 一行不打堆栈：这是预期内的拒绝，不是故障；抛出去的业务异常由全局处理翻成 429
            log.warn("[限流] 触顶拦截 | key={} | 上限 {} 次/{} 秒 | {} {}",
                    key, limit.maxCount(), limit.windowSeconds(), request.getMethod(), request.getRequestURI());
            String message = limit.message().isBlank() ? "操作过于频繁，请稍后再试" : limit.message();
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, message);
        }
        return true;
    }

    /**
     * @return true = 放行
     */
    private boolean acquire(String key, RateLimit limit) {
        long now = System.currentTimeMillis();
        long windowMillis = limit.windowSeconds() * 1000L;
        String member = WORKER_ID + ":" + now + "-" + MEMBER_SEQ.incrementAndGet();
        Long allowed = safeRedis.evalLongOrNull(SLIDING_WINDOW, List.of(key),
                List.of(String.valueOf(now), String.valueOf(windowMillis),
                        String.valueOf(limit.maxCount()), member));
        if (allowed != null) {
            return allowed == 1L;
        }
        // null = Redis 判不出来（异常或熔断）。这里是整条链路上唯一需要决定「不知道怎么办」的地方
        if (!localFallback) {
            return true;
        }
        //兜底桶的额度与 Redis 侧同口径（同 key、同容量、同速率），只是窗口边界不那么精确。
        //空闲多久可以丢桶：至少要把桶装满一次，再宽一个窗口，否则会把还在用的桶提前放掉
        return localRateLimiter.tryAcquire(key, limit.maxCount(),
                (double) limit.maxCount() / limit.windowSeconds(),
                2L * windowMillis * 1_000_000L);
    }

    //计数维度：登录态由 JwtInterceptor 写好，这里只读不算（口径只能有一份，绝不自己解析 token）
    private String resolveSubject(HttpServletRequest request, RateLimit.Dimension dimension) {
        if (dimension == RateLimit.Dimension.IP) {
            return "ip:" + resolveClientIp(request);
        }
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return loginUserId != null ? "u:" + loginUserId : "ip:" + resolveClientIp(request);
    }

    /**
     * 来源地址。默认走 {@code getRemoteAddr()}：它由容器从真实连接取，伪造不了。
     * 打开 use-forwarded-for 后取的是**最右边**那一段，不是第一段 ——
     * nginx 的 {@code $proxy_add_x_forwarded_for} 是把「对端地址」追加到列表尾部，
     * 而左边那几个是客户端自己写的：取第一个能被伪造，取最后一个才是本层反代实际看到的连接方。
     * 边界：前面还套了 CDN 时应该「从右数第 2 个」，本层不猜跳数，那种部署要显式改造
     */
    private String resolveClientIp(HttpServletRequest request) {
        if (useForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank() && !"unknown".equalsIgnoreCase(forwarded)) {
                String[] hops = forwarded.split(",");
                return hops[hops.length - 1].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
