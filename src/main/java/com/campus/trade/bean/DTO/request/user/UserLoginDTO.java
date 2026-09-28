package com.campus.trade.bean.DTO.request.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "用户登录请求参数")
@Data
public class UserLoginDTO {

    @NotBlank(message = "用户名不能为空")
    @Schema(description = "用户名")
    private String username;

    //这里刻意不加长度/字符集校验：登录只需要“能比对”，不需要“够强”。
    //套上注册页那套规则会让历史弱密码账号直接登不上，
    //还多送出一条“密码格式不正确”给试探者当探测信号 —— 与 login() 里“统一错误文案防枚举”是同一条思路
    @NotBlank(message = "密码不能为空")
    @Schema(description = "密码")
    private String password;
}
