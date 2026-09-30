package com.campus.trade.bean.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 统计·商品价格区间分布（看板用）
 */
@Schema(description = "价格区间分布统计行")
@Data
public class PriceRangeCountVo {

    // 区间名由 SQL 的 CASE WHEN 直接给中文（"50-100元"），因为它本身就是给图例看的文字，
    // 不是需要后端再翻译的状态码；分桶边界改了也只是改 SQL，不会和实体常量漂移出两套口径
    @Schema(description = "价格区间名")
    private String rangeName;

    @Schema(description = "落在该区间的商品数（已删除的商品不计入）")
    private Long count;
}
