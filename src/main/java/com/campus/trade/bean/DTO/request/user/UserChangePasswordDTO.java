package com.campus.trade.bean.DTO.request.user;

import com.campus.trade.bean.entry.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "修改密码请求参数")
@Data
public class UserChangePasswordDTO {

    //旧密码只校必填，绝不套强度规则：历史上用 1 位密码注册进来的账号必须能改密自救，
    //把新密码的要求写在旧密码上，等于把它永久锁在门外
    @NotBlank(message = "旧密码不能为空")
    @Schema(description = "旧密码")
    private String oldPassword;

    //与注册页共用 User 里同一组常量：P2-1 原来的状态是“改密校了 6-20、注册什么都没校”，
    //于是弱密码照样能从注册口进来，那条 @Size 只是装饰
    @NotBlank(message = "新密码不能为空")
    @Size(min = User.PASSWORD_MIN_LENGTH, max = User.PASSWORD_MAX_LENGTH,
            message = "密码长度必须在" + User.PASSWORD_MIN_LENGTH + "-" + User.PASSWORD_MAX_LENGTH + "位之间")
    @Pattern(regexp = User.PASSWORD_REGEX, message = "密码不能包含空格")
    @Schema(description = "新密码(6-20位，不含空格)")
    private String newPassword;
}
