package com.campus.trade.bean.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 统计·各分类商品数量（看板用）
 */
@Schema(description = "分类商品数量统计行")
@Data
public class CategoryGoodsCountVo {

    @Schema(description = "分类名称")
    private String name;

    @Schema(description = "该分类下的商品数（已删除的商品不计入）")
    private Long count;
}
