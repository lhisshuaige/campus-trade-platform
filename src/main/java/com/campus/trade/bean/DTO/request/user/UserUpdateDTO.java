package com.campus.trade.bean.DTO.request.user;

import com.campus.trade.bean.entry.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "修改用户资料请求参数")
@Data
public class UserUpdateDTO {

    @Size(max = User.NICKNAME_MAX_LENGTH, message = "昵称过长")
    @Schema(description = "昵称")
    private String nickname;

    //原来这里是硬编码的 ^1[3-9]\d{9}$，与注册页没有任何关系，所以两边可以各改各的。
    //现在两侧都指向 User.PHONE_REGEX，改口径只有一个地方要动
    @Pattern(regexp = User.PHONE_REGEX, message = "手机号格式不正确")
    @Schema(description = "手机号")
    private String phone;

    @Size(max = User.AVATAR_MAX_LENGTH, message = "头像地址过长")
    @Schema(description = "头像URL")
    private String avatar;
}
