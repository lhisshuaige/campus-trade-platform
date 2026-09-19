package com.campus.trade.bean.DTO.request.goods;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(description = "商品分页查询参数")
public class GoodsPageQueryDTO {

    @Schema(description = "商品分类id,可以不传")
    private Long categoryId;

    @Schema(description = "商品关键字,可以不传")
    private String keyword;

    @Schema(description = "用户id,传入则查询该用户的商品,不传则查询所有")
    private Long userId;

    @Min(value = 1, message = "页码最小为1")
    @Schema(description = "当前页码,默认为1")
    private Integer pageNum=1;

    @Min(value = 1, message = "每页条数最小为1")
    @Max(value = 50, message = "每页条数最大为50")
    @Schema(description = "每页显示条数,默认为10")
    private Integer pageSize=10;

}
