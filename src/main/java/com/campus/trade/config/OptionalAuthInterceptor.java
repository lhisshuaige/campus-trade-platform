package com.campus.trade.config;

import com.campus.trade.bean.utils.JwtUtils;
import com.campus.trade.bean.utils.TokenBlacklistUtils;
import com.campus.trade.bean.utils.TokenVersionUtils;
import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 可选认证拦截器：仅挂在公开接口上。
 * 有合法 token 则注入登录态（loginUserId/loginUserRole）；无 token 或 token 失效一律静默按匿名处理，
 * 绝不拦截请求，保证匿名访问不受影响。
 */
@Component
public class OptionalAuthInterceptor implements HandlerInterceptor {

    @Resource
    private JwtUtils jwtUtils;

    @Resource
    private TokenBlacklistUtils tokenBlacklistUtils;

    @Resource
    private TokenVersionUtils tokenVersionUtils;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = request.getHeader("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            // 未携带 token：匿名访问
            return true;
        }
        token = token.substring(7);
        try {
            // 已登出（黑名单）的 token 视为匿名，不注入登录态
            if (tokenBlacklistUtils.contains(token)) {
                return true;
            }
            Claims claims = jwtUtils.parseToken(token);
            Long userId = claims.get("userId", Long.class);
            String role = claims.get("role", String.class);
            Integer tokenVersion = claims.get("tokenVersion", Integer.class);
            // Token 版本不匹配（如已改密）同样视为匿名
            if (tokenVersion == null
                    || tokenVersion.intValue() != tokenVersionUtils.getCurrentVersion(userId)) {
                return true;
            }
            request.setAttribute("loginUserId", userId);
            request.setAttribute("loginUserRole", role);
        } catch (Exception e) {
            // 公开接口：任何解析/校验异常都按匿名处理，绝不影响匿名访问
            return true;
        }
        return true;
    }
}
