package com.campus.trade.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class GoodsVo {
    private Long id;
    private String title;
    private Integer status; // 0-下架 1-上架 2-已售出
    private BigDecimal price;
    private String imgUrl;
    private Long categoryId;
    //列表卡片也要能标出成色与交易地点（二手商品“成色+距离”就是点不点进去的理由）
    private String conditionLevel;
    private String tradeLocation;
    private Integer collectCount;
    //列表里的浏览量只读 DB 存量（最多滞后一个落库周期）：
    //为它逐行去查 Redis，等于给一个展示数字付 10 次命令往返，不值得
    private Integer viewCount;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
