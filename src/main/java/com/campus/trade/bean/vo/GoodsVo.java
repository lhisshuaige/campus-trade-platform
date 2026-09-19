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
    private Integer collectCount;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
