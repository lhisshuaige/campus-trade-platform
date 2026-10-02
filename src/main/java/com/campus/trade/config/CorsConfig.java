package com.campus.trade.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * CORS 白名单（P2-5）。
 * <p>
 * 这个类以前叫 CorsConfig 却同时干着三件事：CORS、四个拦截器的注册、上传目录的静态资源映射（P3-16）。
 * 后两件已经拆到 {@link WebMvcConfig}。拆的理由不是命名洁癖，而是拦截器的 order 0/1/2/3 是本项目的
 * 安全边界定义处，它应该住在一个名字能让人一眼找到的文件里
 * <p>
 * 一个应用可以有多个 {@link WebMvcConfigurer}，Spring 全部收集并依次回调，所以这次拆分不丢配置；
 * 拦截器彼此的先后只由 order 决定，与它注册在哪个类无关
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

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

}
