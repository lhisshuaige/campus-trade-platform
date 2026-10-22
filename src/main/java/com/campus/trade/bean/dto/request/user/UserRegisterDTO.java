package com.campus.trade.bean.dto.request.user;


import com.campus.trade.bean.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "用户注册请求参数")
@Data
public class UserRegisterDTO {
    //注册页与改资料页必须共用 User 里那一组常量：
    //以前只有 UserUpdateDTO 校手机号，注册时能随便填，于是造出过“注册成功但改资料怎么改都报错”的账号
    @NotBlank(message = "用户名不能为空")
    @Size(max = User.USERNAME_MAX_LENGTH,
            message = "用户名长度不能超过" + User.USERNAME_MAX_LENGTH + "位")
    @Pattern(regexp = User.USERNAME_REGEX, message = "用户名只能用字母、数字、下划线或中文")
    @Schema(description = "用户名(不超过20位，字母/数字/下划线/中文，不限长度下限)")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = User.PASSWORD_MIN_LENGTH, max = User.PASSWORD_MAX_LENGTH,
            message = "密码长度必须在" + User.PASSWORD_MIN_LENGTH + "-" + User.PASSWORD_MAX_LENGTH + "位之间")
    @Pattern(regexp = User.PASSWORD_REGEX, message = "密码不能包含空格")
    @Schema(description = "密码(6-20位，不含空格)")
    private String password;


    @Size(max = User.NICKNAME_MAX_LENGTH, message = "昵称过长")
    @Schema(description = "昵称")
    private String nickname;

    //注册防恶意改造的第二个唯一锚点：与 phone 的"选填放过"不同，email 是必填 ——
    //光有格式没用，真实性靠 code 证明；大小写归一在 Service 层做（Redis key / 查重 / 存库同一口径）
    @NotBlank(message = "邮箱不能为空")
    @Size(max = User.EMAIL_MAX_LENGTH, message = "邮箱过长")
    @Pattern(regexp = User.EMAIL_REGEX, message = "邮箱格式不正确")
    @Schema(description = "邮箱(必填，全局唯一)")
    private String email;

    //6 位数字验证码，来自 /user/email/code；校验口径与 EmailCodeServiceImpl 生成侧一致
    @NotBlank(message = "邮箱验证码不能为空")
    @Pattern(regexp = "^\\d{6}$", message = "验证码为6位数字")
    @Schema(description = "邮箱验证码(6位数字)")
    private String code;

    //选填：不传（字段缺省 = null）就放过。传空串会被这条正则拦下 —— 与 UserUpdateDTO 一贯口径相同，
    //不给“空串/NULL 两种都没填”留歧义，否则统计未填手机号要写 IS NULL OR = ''
    @Pattern(regexp = User.PHONE_REGEX, message = "手机号格式不正确")
    @Schema(description = "手机号")
    private String phone;
}
