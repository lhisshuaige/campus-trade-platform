package com.campus.trade.bean.dto.request.user;

import com.campus.trade.bean.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "邮箱验证码发送请求参数")
@Data
public class EmailCodeSendDTO {

    //与 UserRegisterDTO.email 共用 User 里的同组常量（格式+长度都同口径），
    //否则会出现"发码时放过、注册时拦下"的割裂体验（参考 phone 在注册/改资料两侧漂移的老账）
    @NotBlank(message = "邮箱不能为空")
    @Size(max = User.EMAIL_MAX_LENGTH, message = "邮箱过长")
    @Pattern(regexp = User.EMAIL_REGEX, message = "邮箱格式不正确")
    @Schema(description = "邮箱")
    private String email;
}
