package com.campus.trade.bean.vo.request.collect;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "收藏的商品返回给前端的参数")
@Data
public class CollectResponsVo {
    @Schema(description = "收藏id")
    private Long id;

    @Schema(description = "商品id")
    private Long goodsId;

    @Schema(description = "商品名称")
    private String goodsName;

    @Schema(description = "商品价格")
    private Double price;

    @Schema(description = "商品图片")
    private String imgUrl;

    @Schema(description = "商品收藏时间")
    private LocalDateTime createTime;
}
