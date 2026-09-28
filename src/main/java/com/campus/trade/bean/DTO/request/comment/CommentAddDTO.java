package com.campus.trade.bean.DTO.request.comment;

import com.campus.trade.bean.utils.ContentAuditUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "添加评论参数")
@Data
public class CommentAddDTO {

    @NotNull(message = "商品id不能为空")
    @Schema(description = "商品id")
    private Long goodsId;

    @NotBlank(message = "内容不能为空")
    //上限直接引用 ContentAuditUtils 的常量，不再抄一个 200：
    //两处阈值靠拷贝数字保持一致，是“改一处漏一处”的典范（评论长度以前根本没人校）
    //文案里故意不写具体数字，这样常量改了也不会留下一句过时提示
    @Size(max = ContentAuditUtils.COMMENT_MAX_LENGTH, message = "评论内容过长，请精简后再发布")
    @Schema(description = "评论内容，最长 200 字")
    private String content;
}
