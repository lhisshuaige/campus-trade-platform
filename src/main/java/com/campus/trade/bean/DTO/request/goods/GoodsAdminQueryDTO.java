package com.campus.trade.bean.DTO.request.goods;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(description = "管理端商品治理查询参数（与 C 端 GoodsPageQueryDTO 的区别：可指定状态、可跨卖家）")
public class GoodsAdminQueryDTO {

    @Schema(description = "商品状态: 0-下架 1-在售 2-已售出，不传则不限")
    private Integer status;

    @Schema(description = "商品分类id,可以不传")
    private Long categoryId;

    @Schema(description = "商品关键字,模糊匹配标题")
    private String keyword;

    @Schema(description = "卖家id,传入则只看该用户的商品")
    private Long userId;

    @Min(value = 1, message = "页码最小为1")
    @Schema(description = "当前页码,默认为1")
    private Integer pageNum = 1;

    @Min(value = 1, message = "每页条数最小为1")
    @Max(value = 50, message = "每页条数最大为50")
    @Schema(description = "每页显示条数,默认为10")
    private Integer pageSize = 10;
}