package com.campus.trade.bean.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("goods")
@Data
public class Goods {

    // 商品状态：取值域必须和 init.sql 的 chk_goods_status CHECK (status IN (0,1,2)) 一致
    public static final int STATUS_OFF = 0;      // 已下架
    public static final int STATUS_ON_SALE = 1;  // 在售
    public static final int STATUS_SOLD = 2;     // 已售出（被订单占用）

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long categoryId;
    private String title;
    private BigDecimal price;
    @TableField("image")
    private String imgUrl;
    private String description;
    private Integer status;
    private Integer collectCount;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
