package com.campus.trade.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "收藏的商品返回给前端的参数")
@Data
public class CollectVo {

    @Schema(description = "收藏id")
    private Long id;

    @Schema(description = "商品id")
    private Long goodsId;

    @Schema(description = "商品名称")
    private String goodsName;

    // 金额用 BigDecimal，与 Goods/Order/GoodsVo 一致（P3-10）。以前这一列是 Double：
    // 同一个商品的价格在商品列表里是 BigDecimal、在收藏夹里是 Double，而值是从同一张 goods 表 JOIN 出来的，
    // DECIMAL(10,2) 经由 double 中转就是拿二进制浮点存十进制小数（0.1 加起来不等于 0.3 那类事），
    // 展示层拿到的数字与商品详情页差一分，没人能一眼看出是这里丢的
    @Schema(description = "商品价格")
    private BigDecimal price;

    @Schema(description = "商品图片")
    private String imgUrl;

    @Schema(description = "商品收藏时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
