package com.campus.trade.config;

import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.utils.JwtUtils;
import com.campus.trade.bean.utils.TokenBlacklistUtils;
import com.campus.trade.bean.utils.TokenVersionUtils;
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

    @Resource
    private TokenVersionUtils tokenVersionUtils;

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
            String username = claims.get("username", String.class);
            // 一次读取同时拿到「版本号真值」和「账号状态」，不额外增加 Redis 命令
            TokenVersionUtils.AuthState state = tokenVersionUtils.getAuthState(userId);
            // 用户级 Token 版本校验：改密/禁用后版本自增，旧 Token 版本不匹配即判定失效
            Integer tokenVersion = claims.get("tokenVersion", Integer.class);
            if (tokenVersion == null || tokenVersion.intValue() != state.version()) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录已失效，请重新登录");
            }
            // 再兜一层：状态直接读库/读缓存判定。
            // 有了这行，"禁用必须记得 bump 版本"就从【纪律】变成【读路径自己保证的事实】——
            // 未来新增禁用入口、DBA 手改 status、数据导入，最坏也只有缓存 TTL（5 分钟）的窗口
            if (state.status() != 1) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号已被禁用，请联系管理员");
            }
            request.setAttribute("loginUserId", userId);
            request.setAttribute("loginUserRole", role);
            // 操作日志要一个"当时的名字"：从 token 里顺手带出来，不额外查库，也不依赖 user 表还在
            request.setAttribute("loginUsername", username);
            return true;
        } catch (BusinessException e) {
            // 版本校验失败等已知业务异常，保留原始提示
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "token无效或已过期");
        }
    }
}
