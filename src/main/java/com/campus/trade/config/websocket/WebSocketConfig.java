package com.campus.trade.config.websocket;

import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 /ws/chat 原生 WebSocket 端点。
 * ⚠ 注意：握手是 HTTP Upgrade 请求，但不经过 MVC 拦截器链（WebSocketHandlerMapping
 * 不走 WebMvcConfigurer 的 InterceptorRegistry）——所以 JwtInterceptor 对 /ws/** 的排除
 * 是"防未来有人改成统一入口"的保险丝，真正的握手鉴权只有 ChatHandshakeInterceptor 这一道，
 * 冒烟必须带上坏 token 用例（写错=裸奔）
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private ChatWsHandler chatWsHandler;

    @Resource
    private ChatHandshakeInterceptor chatHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        //AllowedOriginPatterns 放开：开发期前端在 5173、后端在 8080，同源校验只会挡住正经跨端口访问；
        //课程项目无敏感 cookie 依赖 WS（身份靠 query token），Origin 不承载安全语义
        registry.addHandler(chatWsHandler, "/ws/chat")
                .addInterceptors(chatHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
