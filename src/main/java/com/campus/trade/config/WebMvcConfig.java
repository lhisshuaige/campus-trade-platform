package com.campus.trade.config;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 装配：拦截器注册 + 静态资源映射。
 * <p>
 * 这两件事原来住在 {@code CorsConfig} 里（P3-16）。类名与职责不符的代价不是"不好看"，而是
 * 本项目最要紧的一段配置藏在了最不容易被打开的文件里：下面那四行 order 0/1/2/3 就是本项目的
 * 安全边界本身（谁先跑、谁对哪些路径生效、为什么限流排在鉴权之后），而找它的人得先猜到一个
 * 叫 CorsConfig 的文件。拆开后 CORS 归 CorsConfig，装配归本类。
 * <p>
 * 拆分的依据：一个应用可以有多个 {@link WebMvcConfigurer} 实现，Spring 会把它们全部收集起来
 * 依次回调，所以把 addCorsMappings 留在原类、把 addInterceptors/addResourceHandlers 搬过来
 * 不会丢配置，也不会改变拦截器之间的执行顺序 —— 顺序只由 order 决定，与注册在哪个类无关
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Resource
    private JwtInterceptor jwtInterceptor;

    @Resource
    private RoleInterceptor roleInterceptor;

    @Resource
    private OptionalAuthInterceptor optionalAuthInterceptor;

    @Resource
    private RateLimitInterceptor rateLimitInterceptor;

    @Value("${upload.path}")
    private String uploadPath;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 可选认证拦截器：仅为公开接口注入"可选"登录态（有 token 且有效才注入，否则按匿名），最先执行
        registry.addInterceptor(optionalAuthInterceptor)
                .addPathPatterns(
                        "/goods/getAll",
                        "/goods/getDetail",
                        "/category/getAll",
                        "/comment/list",
                        // 楼中楼的展开与列表同一种权限形态：游客可看，登录只影响 isOwner 计算
                        "/comment/replies",
                        "/user/profile/**"
                ).order(0);
        // 认证拦截器：校验登录状态（先执行）
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/user/login",
                        "/user/register",
                        // 注册验证码同样是匿名接口：漏掉这行，症状是「前端点发送验证码永远 401」，
                        // 而 Knife4j 上却测得通（手动带了别的 token 就会掩盖这个错误）
                        "/user/email/code",
                        // 健康探针必须排除：Jwt 拦的是 /**，不排除则 /actuator/health 直接 401。
                        // 而容器编排/K8s 探针拿到 401 会判定"服务不健康"并重启容器 —— 可 Redis
                        // 挂了根本不是重启能解决的，等于把一个降级可用的实例变成 CrashLoopBackOff（P3-17）
                        "/actuator/**",
                        "/doc.html",
                        "/webjars/**",
                        "/swagger-resources/**",
                        "/v3/api-docs/**",
                        "/favicon.ico",
                        "/upload/**",
                        // 商品浏览相关：无需登录即可浏览、搜索商品
                        // 排行榜也不挂 optionalAuth：返回内容与人无关，
                        // 多一次 token 解析 + Redis 读只是给最热公开接口白白加成本
                        "/goods/getAll",
                        "/goods/getDetail",
                        "/goods/getCollectRank",
                        "/category/getAll",
                        "/comment/list",
                        // 楼中楼展开：与 /comment/list 同进同退，两处列表漏一处的症状都是「游客看不了回复」
                        "/comment/replies",
                        "/user/profile/**",
                        // 私信 WebSocket：握手是 HTTP Upgrade，本就不走 MVC 拦截器链；
                        // 显式排除是防"将来有人把 /ws 并进统一入口"时握手被 401 拦死。
                        // 真正的鉴权在 ChatHandshakeInterceptor（token 走 query param），
                        // /message/** 不在这里 = 私信 REST 接口默认全部要登录
                        "/ws/**"
                ).order(1);
        // 限流拦截器：仅对标注 @RateLimit 的接口生效（白名单式，没标注解一律放行）
        // 为什么排在认证之后：那个维度要按 userId 计，就得先有人把 loginUserId 写进 request；
        // 否则所有写接口只能按 IP 计，一个机房出口的人就共用一份额度（而登录/注册这两个匿名入口
        // 本来就在 Jwt 的排除名单里，不受这次排序影响）
        // 为什么排在授权之前：先量后权 —— 一个正在被脚本刷的调用方，不该先花一次权限判定再被拒
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**").order(2);
        // 授权拦截器：校验角色权限（最后执行，仅对标注 @RequireRole 的接口生效。原来占的是 order=2）
        registry.addInterceptor(roleInterceptor)
                .addPathPatterns("/**").order(3);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 将 /upload/** 映射到本地磁盘目录，用于访问上传的图片
        registry.addResourceHandler("/upload/**")
                .addResourceLocations("file:" + uploadPath);
    }
}
