package com.campus.trade.bean.DTO.request.user;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "用户注册请求参数")
@Data
public class UserRegisterDTO {
    @NotBlank(message = "用户名不能为空")
    @Schema(description = "用户名")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Schema(description = "密码")
    private String password;


    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "手机号")
    private String phone;
}
