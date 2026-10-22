package com.campus.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.trade.bean.entity.User;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.utils.RedisContent;
import com.campus.trade.bean.utils.SafeRedis;
import com.campus.trade.bean.utils.UserActionLimitUtils;
import com.campus.trade.mapper.UserMapper;
import com.campus.trade.service.EmailCodeService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * 注册邮箱验证码：把「用户名是唯一锚点、脚本可无限注册」换成「注册必须证明你持有一个合法邮箱」。
 *
 * 三层限额各挡一种打法（与 RateLimitInterceptor 的分层思路一致，谁也不重叠）：
 *   1) IP 滑动窗口 —— 控制器上的 @RateLimit（现成注解，这里不重复实现）
 *   2) 同邮箱重发冷却 —— SETNX，挡「按住一个号疯狂重发」
 *   3) 同邮箱当日发码上限 —— INCR 计数桶，挡「换 IP 但邮箱不换」的低成本变体
 *
 * 降级方向与登录限流【相反】：发码链路在 Redis 熔断时直接拒绝（存不进去的码等于没发），
 * 验码链路读不出来时 fail-close（见 verifyAndConsume）。理由：限流是保险丝，坏了不该断电；
 * 验证码是防恶意注册的闸门，放开它整个改造就白做 —— 可用性让位于这道闸门的真实性。
 */
@Slf4j
@Service
public class EmailCodeServiceImpl implements EmailCodeService {

    @Resource
    private SafeRedis safeRedis;

    @Resource
    private UserActionLimitUtils userActionLimitUtils;

    @Resource
    private UserMapper userMapper;

    /** ObjectProvider 而非直接注入：mail starter 的 bean 创建条件含 spring.mail.host 有值，
     *  dev 模式（host 留空）下可能根本没有 JavaMailSender —— 直接 @Resource 会让应用启动即失败 */
    @Resource
    private ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${app.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${app.mail.from:}")
    private String mailFrom;

    // 不是冗余配置：QQ/163 的 SMTP 要求 MAIL FROM 与登录账号一致，不一致直接 501
    // 「Mail from address must be same as authorization user」（实测踩过），
    // 而 JavaMailSenderImpl 在 message.from 为空时【不会】自己回退到 spring.mail.username —— 回退只能在这里做
    @Value("${spring.mail.username:}")
    private String mailAuthUser;

    @Value("${app.email-code.ttl-minutes:5}")
    private int ttlMinutes;

    @Value("${app.email-code.resend-cooldown-seconds:60}")
    private int resendCooldownSeconds;

    @Value("${app.email-code.daily-limit:10}")
    private int dailyLimit;

    @Value("${app.email-code.max-verify-fail:5}")
    private int maxVerifyFail;

    // SecureRandom 不是仪式性选择：java.util.Random 可从少量输出反推种子，那等于给 6 位码开了预测外挂
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public void sendCode(String rawEmail) {
        String email = RedisContent.normalizedEmail(rawEmail);

        // 1. 重发冷却（SETNX + TTL）。null = 熔断未知，保守拒发 —— 下一步的 setSwallow 在熔断期也必败，
        //    与其发一封用户永远验证不了的邮件，不如在这里就明确失败
        Boolean cooled = safeRedis.setIfAbsentOrNull(
                RedisContent.emailCodeCooldownKey(email), "1", Duration.ofSeconds(resendCooldownSeconds));
        if (cooled == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "系统繁忙，暂不可获取验证码，请稍后重试");
        }
        if (!cooled) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "验证码刚刚已发送，请稍后再试");
        }

        // 2. 同邮箱当日发码上限。fail-open 纪律在 UserActionLimitUtils 内部（保险丝坏了不断电）；
        //    能走到这步说明 Redis 活着，所以这层此刻不会放行超限请求
        if (!userActionLimitUtils.tryAcquireByKey(RedisContent.Email_Code_Daily_KEY, email, dailyLimit)) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "该邮箱今日获取验证码次数已达上限，请明日再试");
        }

        // 3. 已注册邮箱直接拒绝：它反正过不了注册，不该白耗一封邮件/一次真实 SMTP 计费
        Long registered = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        if (registered != null && registered > 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "该邮箱已被注册");
        }

        // 4. 6 位码允许前导零（%06d 用满 10^6 空间，nextInt(900000)+100000 会白丢 10% 的码域）
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));

        // 5. 存 Redis，TTL 即有效期 —— 到期自动清理，不需要定时任务（与 RateLimitInterceptor 同纪律）
        if (!safeRedis.setSwallow(RedisContent.emailCodeKey(email), code, Duration.ofMinutes(ttlMinutes))) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "系统繁忙，暂不可获取验证码，请稍后重试");
        }

        // 6. dev 模式：码进日志不出门。这条日志的格式【不能】进生产 —— 默认 app.mail.enabled=false
        //    就是让它只在本地出现的开关，生产忘开 enabled 的后果是「所有人收不到码」，比泄露更难堪
        if (!mailEnabled) {
            log.info("【邮件未启用·dev模式】注册验证码 email={} code={} 有效期{}分钟", email, code, ttlMinutes);
            return;
        }
        sendMail(email, code);
    }

    @Override
    public void verifyAndConsume(String rawEmail, String code) {
        String email = RedisContent.normalizedEmail(rawEmail);
        String key = RedisContent.emailCodeKey(email);
        String expected = safeRedis.getOrNull(key);
        if (expected == null) {
            // 「没存过」与「Redis 熔断读不出」都给 null，这里刻意不区分、一律拒：
            // fail-close 是这条链路的闸门属性决定的 —— 证明不了邮箱所有权就拒绝注册，
            // 与 UserActionLimitUtils「保险丝坏了不断电」是相反方向，别把它当成同款 bug 改掉
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "邮箱验证码已过期或未发送，请重新获取");
        }
        if (!expected.equals(code)) {
            String failKey = RedisContent.emailCodeFailKey(email);
            Long fails = safeRedis.incrOrNull(failKey);
            if (fails != null && fails == 1L) {
                // 计数窗口 = 码的寿命，刻意不续期：每次失败都续 TTL 等于把窗口续到猜完为止
                safeRedis.expireSwallow(failKey, Duration.ofMinutes(ttlMinutes));
            }
            if (fails != null && fails >= maxVerifyFail) {
                // 6 位码只有 100 万组合，不设错次上限等于允许慢速穷举；作废后想继续只能重新发码（重新过冷却+日限）
                safeRedis.deleteSwallow(key);
                safeRedis.deleteSwallow(failKey);
                throw new BusinessException(ErrorCode.BUSINESS_ERROR, "验证码错误次数过多，该验证码已作废，请重新获取");
            }
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "验证码错误");
        }
        // 验证通过即消费：同一码用一次就没了，拿它注册第二个账号是不可能的
        safeRedis.deleteSwallow(key);
        safeRedis.deleteSwallow(RedisContent.emailCodeFailKey(email));
    }

    private void sendMail(String email, String code) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.error("app.mail.enabled=true 但 JavaMailSender 不可用（检查 spring.mail.host 是否配置）");
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "邮件服务未就绪，请稍后重试");
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            // from：显式配置优先，留空回退 SMTP 登录账号（QQ/163 只接受 from==授权账号，见 mailAuthUser 注释）
            String from = (mailFrom != null && !mailFrom.isBlank()) ? mailFrom : mailAuthUser;
            if (from != null && !from.isBlank()) {
                message.setFrom(from);
            }
            message.setTo(email);
            message.setSubject("CampusTrade 注册验证码");
            message.setText("您的注册验证码为：" + code
                    + "，" + ttlMinutes + " 分钟内有效。\n"
                    + "若非本人操作，请忽略本邮件。");
            sender.send(message);
        } catch (MailException e) {
            // 码已存进 Redis：用户过了冷却期还能重新发。这里不回滚码、不吞异常继续假装成功
            log.error("验证码邮件发送失败 email={}", email, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "邮件发送失败，请稍后重试");
        }
    }
}
