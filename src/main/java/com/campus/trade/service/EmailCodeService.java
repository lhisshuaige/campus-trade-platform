package com.campus.trade.service;

public interface EmailCodeService {

    /**
     * 发送注册邮箱验证码：三层限额（IP 由控制器 @RateLimit 挡、重发冷却、每邮箱当日上限）+
     * 已注册预查，通过后写 Redis（TTL 即有效期）并发信。
     * app.mail.enabled=false 时不发信，验证码进日志 —— dev 模式跑通全链路用。
     */
    void sendCode(String email);

    /**
     * 校验并【消费】验证码：通过即删，同一个码不可能注册两次；连续输错到达上限则码作废。
     * 校验失败一律抛 BusinessException，调用方（register）不需要再看返回值。
     */
    void verifyAndConsume(String email, String code);
}
