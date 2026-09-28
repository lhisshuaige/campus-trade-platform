package com.campus.trade.bean.DTO.request.comment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(description = "管理端评论治理查询参数")
public class CommentAdminQueryDTO {

    @Schema(description = "商品id，传入则只看该商品的评论")
    private Long goodsId;

    @Schema(description = "评论人id，传入则只看该用户的评论")
    private Long userId;

    @Schema(description = "内容关键字，模糊匹配")
    private String keyword;

    @Min(value = 1, message = "页码最小为1")
    @Schema(description = "当前页码,默认为1")
    private Integer pageNum = 1;

    @Min(value = 1, message = "每页条数最小为1")
    @Max(value = 50, message = "每页条数最大为50")
    @Schema(description = "每页显示条数,默认为10")
    private Integer pageSize = 10;
}