package com.campus.trade.service.impl;

import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.utils.RedisContent;
import com.campus.trade.bean.utils.SafeRedis;
import com.campus.trade.bean.utils.UserActionLimitUtils;
import com.campus.trade.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2：邮箱验证码的限额顺序、一次性消费与降级方向。
 * <p>
 * 与 UserServiceImplSecurityTest 同一手法（不启容器、不起 Redis、不连 SMTP），但这里可以
 * 直接 @InjectMocks：本类不继承 MyBatis-Plus ServiceImpl，没有 default 方法继承链的问题；
 * @Value 字段仍要手工灌（配置缺省值被改动会连带行为漂移，所以固定在这里）。
 * <p>
 * 断言重点不是「返回了什么」而是「什么没发生」：冷却拒发时不许存码、熔断时不许发信 ——
 * 这类缺席断言正是防恶意逻辑在重构中最容易无声退化的地方。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("L2 EmailCodeServiceImpl：限额顺序 / 一次性消费 / fail-close 方向")
class EmailCodeServiceImplTest {

    private static final String EMAIL = "student@campus.edu.cn";

    @Mock
    private SafeRedis safeRedis;
    @Mock
    private UserActionLimitUtils userActionLimitUtils;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ObjectProvider<JavaMailSender> mailSenderProvider;

    @InjectMocks
    private EmailCodeServiceImpl service;

    @BeforeEach
    void fillConfigDefaults() {
        // 给的就是 application.yaml 里的缺省值，改配置会在这里先红
        ReflectionTestUtils.setField(service, "mailEnabled", false);
        ReflectionTestUtils.setField(service, "mailFrom", "");
        ReflectionTestUtils.setField(service, "ttlMinutes", 5);
        ReflectionTestUtils.setField(service, "resendCooldownSeconds", 60);
        ReflectionTestUtils.setField(service, "dailyLimit", 10);
        ReflectionTestUtils.setField(service, "maxVerifyFail", 5);
    }

    private void stubHappyPathUntilStore(boolean storeOk) {
        when(safeRedis.setIfAbsentOrNull(RedisContent.emailCodeCooldownKey(EMAIL), "1", Duration.ofSeconds(60)))
                .thenReturn(true);
        when(userActionLimitUtils.tryAcquireByKey(RedisContent.Email_Code_Daily_KEY, EMAIL, 10)).thenReturn(true);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(safeRedis.setSwallow(eq(RedisContent.emailCodeKey(EMAIL)), anyString(), eq(Duration.ofMinutes(5))))
                .thenReturn(storeOk);
    }

    // ===================== 发码：三层限额各挡各的 =====================

    @Test
    @DisplayName("dev 模式发码：大小写归一后进 Redis，6 位码，绝不碰 JavaMailSender")
    void sendCodeDevModeStoresNormalizedAndNeverTouchesMailSender() {
        stubHappyPathUntilStore(true);

        service.sendCode("Student@Campus.EDU.cn");

        ArgumentCaptor<String> codeCap = ArgumentCaptor.forClass(String.class);
        verify(safeRedis).setSwallow(eq("email:code:" + EMAIL), codeCap.capture(), eq(Duration.ofMinutes(5)));
        assertTrue(codeCap.getValue().matches("\\d{6}"), "验证码必须是 6 位数字，与注册 DTO 的 @Pattern 同口径");
        verify(mailSenderProvider, never()).getIfAvailable();
    }

    @Test
    @DisplayName("冷却未过（SETNX false）直接拒，码都不该生成、不该存")
    void sendCodeRejectedWhileInCooldown() {
        when(safeRedis.setIfAbsentOrNull(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.sendCode(EMAIL));

        assertTrue(ex.getMsg().contains("稍后再试"));
        verify(safeRedis, never()).setSwallow(anyString(), anyString(), any(Duration.class));
        // 冷却排在日限之前，都不该消耗
        verify(userActionLimitUtils, never()).tryAcquireByKey(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("SETNX 返回 null（熔断未知）时拒发而不是放行：存不进去的码等于没发")
    void sendCodeRejectedWhenCooldownStateUnknown() {
        when(safeRedis.setIfAbsentOrNull(anyString(), anyString(), any(Duration.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.sendCode(EMAIL));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("当日上限用完拒发，不再写冷却/存码")
    void sendCodeRejectedWhenDailyLimitExhausted() {
        when(safeRedis.setIfAbsentOrNull(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(userActionLimitUtils.tryAcquireByKey(RedisContent.Email_Code_Daily_KEY, EMAIL, 10)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.sendCode(EMAIL));

        assertTrue(ex.getMsg().contains("上限"));
        verify(safeRedis, never()).setSwallow(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("已注册邮箱不发码：反正过不了注册，别白耗一封邮件")
    void sendCodeRejectedForAlreadyRegisteredEmail() {
        when(safeRedis.setIfAbsentOrNull(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(userActionLimitUtils.tryAcquireByKey(RedisContent.Email_Code_Daily_KEY, EMAIL, 10)).thenReturn(true);
        when(userMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.sendCode(EMAIL));

        assertTrue(ex.getMsg().contains("该邮箱已被注册"));
        verify(safeRedis, never()).setSwallow(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis 存码失败（setSwallow false = 真没写进去）时明确报忙，不假装已发送")
    void sendCodeFailsFastWhenStoreFails() {
        when(safeRedis.setIfAbsentOrNull(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(userActionLimitUtils.tryAcquireByKey(RedisContent.Email_Code_Daily_KEY, EMAIL, 10)).thenReturn(true);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(safeRedis.setSwallow(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.sendCode(EMAIL));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE.getCode(), ex.getCode());
        verify(mailSenderProvider, never()).getIfAvailable();
    }

    // ===================== 验码：闸门语义 =====================

    @Test
    @DisplayName("读不到码（未发/过期/熔断都是 null）一律拒 —— 这里刻意 fail-close，别当 bug 改成放行")
    void verifyRejectsWhenCodeAbsentOrRedisUnknown() {
        when(safeRedis.getOrNull(RedisContent.emailCodeKey(EMAIL))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyAndConsume(EMAIL, "123456"));

        assertTrue(ex.getMsg().contains("已过期或未发送"));
        verify(safeRedis, never()).deleteSwallow(anyString());
    }

    @Test
    @DisplayName("错一次计一次，TTL 只在第一次钉住（续期等于把窗口续到猜完）")
    void firstWrongAttemptPinsFailCounterTtl() {
        when(safeRedis.getOrNull(RedisContent.emailCodeKey(EMAIL))).thenReturn("123456");
        when(safeRedis.incrOrNull(RedisContent.emailCodeFailKey(EMAIL))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyAndConsume(EMAIL, "000000"));

        assertTrue(ex.getMsg().contains("验证码错误"));
        verify(safeRedis).expireSwallow(RedisContent.emailCodeFailKey(EMAIL), Duration.ofMinutes(5));
        verify(safeRedis, never()).deleteSwallow(RedisContent.emailCodeKey(EMAIL));
    }

    @Test
    @DisplayName("错满上限即删码作废：6 位码只有 100 万组合，不设上限就是允许慢速穷举")
    void wrongCodeAtLimitInvalidatesTheCode() {
        when(safeRedis.getOrNull(RedisContent.emailCodeKey(EMAIL))).thenReturn("123456");
        when(safeRedis.incrOrNull(RedisContent.emailCodeFailKey(EMAIL))).thenReturn(5L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyAndConsume(EMAIL, "000000"));

        assertTrue(ex.getMsg().contains("作废"));
        verify(safeRedis).deleteSwallow(RedisContent.emailCodeKey(EMAIL));
        verify(safeRedis).deleteSwallow(RedisContent.emailCodeFailKey(EMAIL));
    }

    @Test
    @DisplayName("验通过即删码（一次性）：同一个码注册第二个账号不可能")
    void correctCodeIsConsumed() {
        when(safeRedis.getOrNull(RedisContent.emailCodeKey(EMAIL))).thenReturn("123456");

        assertDoesNotThrow(() -> service.verifyAndConsume("Student@Campus.EDU.cn", "123456"));

        verify(safeRedis).deleteSwallow(RedisContent.emailCodeKey(EMAIL));
        verify(safeRedis).deleteSwallow(RedisContent.emailCodeFailKey(EMAIL));
    }
}
