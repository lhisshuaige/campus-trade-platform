package com.campus.trade.config.websocket;

import com.campus.trade.bean.utils.JwtUtils;
import com.campus.trade.bean.utils.TokenBlacklistUtils;
import com.campus.trade.bean.utils.TokenVersionUtils;
import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * 握手鉴权：与 JwtInterceptor 同一套验证链，差别只在 token 的来源——
 * 浏览器 WebSocket 构造函数带不了自定义 Header，只能走 query param（?token=）。
 * ⚠ 代价：URL 可能被 access log / 代理日志留痕，课程/内网场景可接受，公网要换成
 * 一次性 ticket 或 Sec-WebSocket-Protocol 方案。
 * 验证不过就拒绝握手（返回 false），此时连接根本建立，前端重连逻辑自然按"未登录"处理
 */
@Slf4j
@Component
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

    @Resource
    private JwtUtils jwtUtils;

    @Resource
    private TokenBlacklistUtils tokenBlacklistUtils;

    @Resource
    private TokenVersionUtils tokenVersionUtils;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = UriComponentsBuilder.fromHttpRequest(request).build()
                .getQueryParams().getFirst("token");
        if (token == null || token.isBlank()) {
            log.info("私信握手被拒：缺少 token");
            return false;
        }
        //已登出的 token 不能用来挂常驻连接：否则"退出登录"之后旧标签页还能继续收消息
        if (tokenBlacklistUtils.contains(token)) {
            log.info("私信握手被拒：token 在黑名单");
            return false;
        }
        try {
            Claims claims = jwtUtils.parseToken(token);
            Long userId = claims.get("userId", Long.class);
            TokenVersionUtils.AuthState state = tokenVersionUtils.getAuthState(userId);
            Integer tokenVersion = claims.get("tokenVersion", Integer.class);
            if (tokenVersion == null || tokenVersion.intValue() != state.version()) {
                log.info("私信握手被拒：token 版本不匹配 userId={}", userId);
                return false;
            }
            if (state.status() != 1) {
                log.info("私信握手被拒：账号被禁用 userId={}", userId);
                return false;
            }
            //ChatWsHandler 从这里取身份；不放在 URL/消息体里，连接建立后就与 token 解耦
            attributes.put("wsUserId", userId);
            return true;
        } catch (Exception e) {
            log.info("私信握手被拒：token 解析失败 {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        //握手失败（beforeHandshake 返回 false）时 Spring 调这里且 exception==null：
        //没有可补救的动作，日志已在 beforeHandshake 记过，不重复
    }
}
