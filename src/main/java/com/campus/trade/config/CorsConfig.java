package com.campus.trade.config;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

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

    // ==== CORS 白名单（P2-5）====
    // 原来是 allowedOriginPatterns("*")：任何网页都能让自己的 JS 向本站发跨域请求。
    // 默认值只放本机来源 = fail-closed：生产忘配时是「前端调不通、立刻被发现」，
    // 而不是「悄悄向全网开着」。要放真实域名走配置覆盖，不改代码（prod profile 里已留好入口）
    // 用 allowedOriginPatterns 而非 allowedOrigins：只有前者支持 http://localhost:* 这种带端口的通配
    @Value("${app.cors.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
    private List<String> allowedOriginPatterns;

    // 凭据开关默认关：本站登录态走 Authorization 头里的 JWT，不依赖 Cookie。
    // 开着它 = 把「浏览器自动带上 cookie」这条通道也开给白名单里的每一个来源，
    // 而现在没有任何一个来源需要它；将来真上 Cookie 会话时再按环境打开
    @Value("${app.cors.allow-credentials:false}")
    private boolean allowCredentials;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // allowedHeaders 刻意不收紧（保持默认的 *）：真正有害的是 origin 与凭据，
        // 而非自定义请求头 —— 非简单头本来就会触发预检，由服务端拒绝即可；
        // 把它写死只会让前端下次加一个 X-Trace-Id 时收到一个难排查的 CORS 错误
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOriginPatterns.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD")
                .allowCredentials(allowCredentials)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 可选认证拦截器：仅为公开接口注入"可选"登录态（有 token 且有效才注入，否则按匿名），最先执行
        registry.addInterceptor(optionalAuthInterceptor)
                .addPathPatterns(
                        "/goods/getAll",
                        "/goods/getDetail",
                        "/category/getAll",
                        "/comment/list",
                        "/user/profile/**"
                ).order(0);
        // 认证拦截器：校验登录状态（先执行）
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/user/login",
                        "/user/register",
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
                        "/user/profile/**"
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
