package com.campus.trade.config;

import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.utils.JwtUtils;
import com.campus.trade.bean.utils.TokenBlacklistUtils;
import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Resource
    private JwtUtils jwtUtils;

    @Resource
    private TokenBlacklistUtils tokenBlacklistUtils;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = request.getHeader("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或token已过期");
        }
        token = token.substring(7);
        // token 已登出（存在于黑名单），直接拒绝
        if (tokenBlacklistUtils.contains(token)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录已失效，请重新登录");
        }
        try {
            Claims claims = jwtUtils.parseToken(token);
            Long userId = claims.get("userId", Long.class);
            String role = claims.get("role", String.class);
            request.setAttribute("loginUserId", userId);
            request.setAttribute("loginUserRole", role);
            return true;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "token无效或已过期");
        }
    }
}
