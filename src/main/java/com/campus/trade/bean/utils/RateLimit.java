package com.campus.trade.bean.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级频率限制：标在 Controller 方法上，由 {@code RateLimitInterceptor} 在【进 Controller 之前】判定。
 *
 * 与已有的两层额度控制的区别（三层各管一件事，不是重复实现）：
 * 1) {@code @RateLimit}（本注解）管**瞬时频率**：「一分钟内这个入口最多被打多少次」，挡的是脚本连点/循环调用。
 * 2) {@code UserActionLimitUtils} 的每日计数管**当日总量**：「这个账号今天一共能登录登出多少次」，
 *    时间尺度是 24 小时，对「10 秒内打 50 次」完全无感。
 * 3) {@code UserActionLimitUtils} 的登录失败锁定管**单账号被爆破**，且只在密码校验失败时才计数。
 *
 * 三个刻意的设计取舍：
 * 1) **白名单式**：没标注解的接口一律不限流。宁可漏几个入口，也不要给全局加一个「某天突然把正常用户拦在门外」的默认闸。
 * 2) **额度写在注解里而不是配置里**：这几个数字是产品口径（一人一分钟能下几单），不该由运维临时改；
 *    要改就改代码、走一次发布、留一条 git 记录。代价见 5.11 的边界一节。
 * 3) **maxCount 无默认值**：逼标注解的人显式想过「这条接口允许多快」，而不是抄一个别人填过的 10。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 计数维度 */
    enum Dimension {
        /** 有登录态按 userId 计，没有则按来源 IP 计：需要登录的写接口用这个 */
        USER_OR_IP,
        /** 只按来源 IP 计：登录/注册这类必然拿不到登录态的入口用这个 */
        IP
    }

    /** 窗口内最多允许多少次请求 */
    int maxCount();

    /** 窗口长度（秒），默认 1 分钟 */
    int windowSeconds() default 60;

    /** 计数维度 */
    Dimension dimension() default Dimension.USER_OR_IP;

    /**
     * 业务标识，进 Redis key 用。多个端点填同一个值 = 共用一份额度
     * （订单的确认/收货/取消就共用 {@code order:action}：刷的是同一个用户的操作频率，不必三份预算）。
     * 留空则由拦截器用「类名.方法名」兜底
     */
    String key() default "";

    /** 触顶提示语，留空给一句通用文案。提示语里不要出现「还剩几秒」之外的内部细节 */
    String message() default "";
}
