package com.campus.trade.bean.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 统计·商品状态分布（看板用）
 */
@Schema(description = "商品状态分布统计行")
@Data
public class GoodsStatusCountVo {

    @Schema(description = "状态原值: 0-下架 1-在售 2-已售出")
    private Integer status;

    // 中文标签由服务端给（口径来自 Goods.STATUS_LABELS）：
    // 原来只返一个裸数字，等于把「0/1/2 分别是什么意思」这份映射塞进前端，
    // 而值域是后端定义的，两处漂移时看板上的图例会悄悄指错状态
    @Schema(description = "状态中文标签，可直接当图例文字")
    private String statusLabel;

    @Schema(description = "该状态的商品数（已删除的商品不计入）")
    private Long count;
}
