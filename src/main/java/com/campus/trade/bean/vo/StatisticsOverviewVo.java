package com.campus.trade.bean.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 平台数据统计概览
 */
@Schema(description = "平台数据统计概览")
@Data
public class StatisticsOverviewVo {

    @Schema(description = "用户总数")
    private Long userCount;

    @Schema(description = "商品总数")
    private Long goodsCount;

    @Schema(description = "在售商品数")
    private Long onSaleCount;

    @Schema(description = "订单总数")
    private Long orderCount;

    @Schema(description = "已完成订单数")
    private Long completedOrderCount;

    @Schema(description = "成交总额")
    private BigDecimal totalAmount;
}
