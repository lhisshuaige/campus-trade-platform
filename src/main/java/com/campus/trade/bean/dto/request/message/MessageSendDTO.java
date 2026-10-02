package com.campus.trade.bean.dto.request.message;

import com.campus.trade.bean.entity.Message;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "私信发送参数")
@Data
public class MessageSendDTO {

    @NotNull(message = "收件人id不能为空")
    @Schema(description = "收件人用户id")
    private Long toUserId;

    //可空：会话是连续的，首条从商品详情带上下文，后续追问不必重复携带
    @Schema(description = "关联商品id(可空)")
    private Long goodsId;

    @NotBlank(message = "内容不能为空")
    //上限直接引用 Message.CONTENT_MAX_LENGTH，与 V5 的 VARCHAR(500) 同口径：
    //阈值靠拷贝数字保持一致是"改一处漏一处"的典范（与 CommentAddDTO 同款做法）
    @Size(max = Message.CONTENT_MAX_LENGTH, message = "私信内容过长，请精简后再发送")
    @Schema(description = "私信正文，最长 500 字")
    private String content;
}
