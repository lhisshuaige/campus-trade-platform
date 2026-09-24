package com.campus.trade.bean.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("`order`")
@Data
public class Order {

    // 订单状态：取值域必须和 init.sql 的 chk_order_status CHECK (status IN (0,1,2,3)) 一致
    public static final int STATUS_PENDING = 0;    // 待卖家确认
    public static final int STATUS_CONFIRMED = 1;  // 卖家已确认
    public static final int STATUS_COMPLETED = 2;  // 已完成（买家确认收货）
    public static final int STATUS_CANCELLED = 3;  // 已取消

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long goodsId;
    private Long sellerId;
    private Long buyerId;
    private BigDecimal price;
    private Integer status;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
