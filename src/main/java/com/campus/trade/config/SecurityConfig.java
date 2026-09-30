package com.campus.trade.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 在本项目里只承担两件事：提供一个 {@link PasswordEncoder}、把会话定成无状态。
 *
 * 【鉴权不归它管】—— 登录校验是 {@code JwtInterceptor}，角色校验是 {@code RoleInterceptor} + {@code @RequireRole}。
 * 所以下面那句 {@code anyRequest().permitAll()} 不是“忘了配”，而是刻意不让两套体系并存：
 * 如果登录校验也交给 Security，就得同时维护「Security 链」与「MVC 拦截器」两条顺序，
 * 一个接口被拒时先问“是哪条链拒的”，这个复杂度在本项目没有回报（自研那套已经把 token_version、
 * 黑名单、降级方向这三件事做完了，换成 Security 只是把它们换一套 API 重写）。
 *
 * 代价要说清：这是一个“规范上不标准”的选择。标准做法是把登录校验做成 {@code OncePerRequestFilter}
 * 放进 Security 链、用 {@code @PreAuthorize} 做授权。现在这个 permitAll 容易被误读成“项目没做鉴权”，
 * 所以这段注释本身就是交付物的一部分。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 密码编码器交给容器（P2-10）。原来是在 {@code UserServiceImp} 里 {@code new BCryptPasswordEncoder()}：
     * 那样“本站用什么强度加密”就在两处各说各话 —— Security 里那套配置和那个实例没有任何关系，
     * 将来要调 cost factor 或换 Argon2，得先记起“还有第二个实例存在”。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        //强度用默认 10（一次比对约 100ms）。这 100ms 不是浪费，它是登录接口自己的抗爆破预算：
        //调到 12 就是 4 倍成本，但正常用户登录也会慢到 400ms。真要把预算花在刀刃上，
        //靠的是 P0-9 的失败锁定与这次的 IP 限流，不是把一个固定值调到没人受得了
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 登录态在 Authorization 头里，不依赖 Cookie，所以没有 CSRF 的攻击面（跨站请求带不上这个头）
            .csrf(csrf -> csrf.disable())
            // STATELESS 是本配置的真正作用：不建 HttpSession，也就不会在服务器端存任何会话状态
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
