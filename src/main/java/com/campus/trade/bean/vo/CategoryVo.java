package com.campus.trade.bean.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "商品分类返回给前端的参数")
@Data
public class CategoryVo {

    @Schema(description = "分类id")
    private Long id;

    @Schema(description = "分类名称")
    private String name;

    @Schema(description = "排序值")
    private Integer sort;
}
