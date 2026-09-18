package com.campus.trade.bean.vo.request.goods;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "添加商品请求参数")
@Data
public class GoodsAddVo {

    @NotNull(message = "商品分类id不能为空,请选择商品id")
    @Schema(description = "商品分类id")
    private Long categoryId;

    @NotBlank(message = "商品标题不能为空")
    @Schema(description = "商品标题")
    private String title;

    @NotNull(message = "商品价格不能为空")
    @Schema(description = "商品价格")
    private BigDecimal price;

    @Schema(description = "商品描述")
    private String description;

    @Schema(description = "商品图片地址")
    private String imgUrl;
}
