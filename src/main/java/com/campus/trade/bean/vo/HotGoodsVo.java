package com.campus.trade.bean.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 统计·热门商品排行（按收藏数降序，看板用）
 */
@Schema(description = "热门商品排行行")
@Data
public class HotGoodsVo {

    @Schema(description = "商品ID")
    private Long id;

    @Schema(description = "商品标题")
    private String title;

    // 金额一律 BigDecimal：这里以前跟着 Map 走，值是 BigDecimal 却没人声明，
    // 序列化出来的小数位数取决于 DB 返回的 scale，声明出来才有口径可谈
    @Schema(description = "商品价格")
    private BigDecimal price;

    // 字段名沿用 image 而不是 imgUrl：这是看板已经在用的 key 名，
    // 内部实体叫 imgUrl 是它的事，出参名字稳定比名字统一更值钱
    @Schema(description = "商品图片地址")
    private String image;

    @Schema(description = "收藏数")
    private Integer collectCount;
}
