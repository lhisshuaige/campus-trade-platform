package com.campus.trade.bean.DTO.request.comment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "添加评论参数")
@Data
public class CommentAddDTO {

    @NotNull(message = "商品id不能为空")
    @Schema(description = "商品id")
    private Long goodsId;

    @NotBlank(message = "内容不能为空")
    @Schema(description = "评论内容")
    private String content;
}
