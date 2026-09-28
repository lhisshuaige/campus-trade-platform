package com.campus.trade.bean.DTO.request.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(description = "管理端订单查询参数")
public class OrderAdminQueryDTO {

    @Schema(description = "订单状态: 0-待确认 1-已确认 2-已完成 3-已取消，不传则不限")
    private Integer status;

    @Schema(description = "订单号，精确匹配")
    private String orderNo;

    @Schema(description = "买家id")
    private Long buyerId;

    @Schema(description = "卖家id")
    private Long sellerId;

    @Schema(description = "商品id")
    private Long goodsId;

    @Min(value = 1, message = "页码最小为1")
    @Schema(description = "当前页码,默认为1")
    private Integer pageNum = 1;

    @Min(value = 1, message = "每页条数最小为1")
    @Max(value = 50, message = "每页条数最大为50")
    @Schema(description = "每页显示条数,默认为10")
    private Integer pageSize = 10;
}