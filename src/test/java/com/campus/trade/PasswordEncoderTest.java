package com.campus.trade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码编码器的第一批断言（P3-11）。
 * <p>
 * 它替代了原来那个 {@code CampusTradeApplicationTests.contextLoads}：那个方法用
 * {@code System.out.println} 打印加密结果和匹配结果、没有任何断言，却带着
 * {@code @SpringBootTest} + {@code @Autowired UserMapper} —— 于是它是全项目唯一一个测试，
 * 而这个唯一测试**必须连上真实 MySQL 和 Redis 才能跑**。CI 上必然红，本地没起中间件时也是红的，
 * 结果就是"跑一次测试"这件事本身被放弃了。
 * <p>
 * 而它真正想验的东西（BCrypt 能加密、能校验）根本不需要 Spring 上下文，
 * 所以这里把它降级成一个纯 JUnit 测试：不启动容器、不连库， {@code mvn test} 在任何机器上都能过
 */
class PasswordEncoderTest {

    @Test
    @DisplayName("密文能校验回原文，且长度固定为 60 —— user.password 那列 VARCHAR(255) 不是随手写的")
    void encodeAndMatchRoundTrip() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String raw = "123456";

        String encoded = encoder.encode(raw);

        assertEquals(60, encoded.length(), "BCrypt 密文是定长 60（$2a$10$ + 22 位盐 + 31 位摘要）");
        assertTrue(encoded.startsWith("$2a$10$"), "默认 cost factor 是 10，改成 12 会让登录慢 4 倍，这里把它钉住");
        assertTrue(encoder.matches(raw, encoded));
    }

    @Test
    @DisplayName("同一个密码两次加密结果不同 —— 说明真的带了随机盐，不是可预告的哈希")
    void sameRawPasswordProducesDifferentCiphertext() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        assertNotEquals(encoder.encode("123456"), encoder.encode("123456"));
    }

    @Test
    @DisplayName("错密码不匹配，包括只差一个字符的情况（防撞库时逐位试探）")
    void wrongPasswordDoesNotMatch() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String encoded = encoder.encode("123456");

        assertFalse(encoder.matches("1234567", encoded));
        assertFalse(encoder.matches("12345a", encoded));
        // 空串必须走不通：登录 DTO 的 @NotBlank 只在带 @Valid 的入口生效，这里是最后一道
        assertFalse(encoder.matches("", encoded));
    }
}
