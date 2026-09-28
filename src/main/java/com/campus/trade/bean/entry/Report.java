package com.campus.trade.bean.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 内容举报。它是「用户发现的问题」与「管理员处置」之间唯一的凭据：
 * 没有这张表，内容治理就只能靠管理员自己翻评论列表，用户看到违规内容无能为力。
 */
@Data
@TableName("report")
public class Report {

    // 被举报对象类型：取值域必须和 init.sql 的 chk_report_target_type 一致
    public static final String TARGET_GOODS = "goods";
    public static final String TARGET_COMMENT = "comment";

    // 处理状态：取值域必须和 init.sql 的 chk_report_status 一致
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_HANDLED = 1;
    public static final int STATUS_REJECTED = 2;

    /** 举报理由长度上限：与 init.sql 的 reason VARCHAR(500) 一致 */
    public static final int REASON_MAX_LENGTH = 500;

    @TableId(type = IdType.AUTO)
    private Long id;
    //举报人
    private Long userId;
    private String targetType;
    //多态关联的 id：targetType=goods 时是商品 id，=comment 时是评论 id。不建外键，见 init.sql 注释
    private Long targetId;
    private String reason;
    private Integer status;
    //处理人与处理时间：待处理时为 null，"谁处置的"本身就是治理追责的凭据
    private Long handlerId;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime handleTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
