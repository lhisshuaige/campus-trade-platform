package com.campus.trade.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class GoodsDetailVo {
    private Long id;
    private String title;
    private BigDecimal price;
    private String imgUrl;
    private String description;
    private Long categoryId;
    private Integer collectCount;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

}
