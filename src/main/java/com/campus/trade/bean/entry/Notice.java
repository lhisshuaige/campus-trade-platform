package com.campus.trade.bean.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内信。一条消息只有两个命运：产生、被接收者读掉，所以它不需要列表缓存
 * （个人维度 + 写入即变化，缓存它等于给每次互动多一次失效动作，见 RedisContent 里的反面清单）
 */
@Data
@TableName("notice")
public class Notice {

    // 消息类型：取值域必须和 init.sql 的 chk_notice_type 一致
    public static final String TYPE_ORDER = "order";
    public static final String TYPE_COLLECT = "collect";
    public static final String TYPE_COMMENT = "comment";
    //系统通报：不是“有人互动了”但必须告知的机器消息（举报处理结果、账号被处置）
    public static final String TYPE_SYSTEM = "system";

    // 已读标记用 0/1 而不是 boolean：与 DB 的 TINYINT + CHECK 约束同一口径
    public static final int UNREAD = 0;
    public static final int READ = 1;

    // 正文长度上限：与 init.sql 的 content VARCHAR(500) 一致，超长直接截断而不是报错——
    // 通知是附属信息，不该因为商品标题特别长就把"下单"这个主业务打回 400
    public static final int CONTENT_MAX_LENGTH = 500;

    @TableId(type = IdType.AUTO)
    private Long id;
    //接收者。注意不是"发起人"：一条"有人收藏了你"的消息，user_id 是被收藏的那个人
    private Long userId;
    private String type;
    private String title;
    private String content;
    //关联业务 id：type=order 时是订单 id，type=collect/comment 时是商品 id。
    //多态关联，故意不建外键（一个列不能引用两张表），所以允许悬空
    private Long bizId;
    @TableField("is_read")
    private Integer isRead;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
