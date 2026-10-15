package com.campus.trade.bean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 私信：用户 ↔ 用户（产品口径上围绕商品/卖家）的一对一消息，一行就是一条。
 * 与站内信 Notice 的区别：Notice 是"系统替某人通知你"（发起人只用来跳过自购，不落库），
 * Message 是"某人真的在跟你说话"（from_id/to_id 都是数据本体），所以没法复用那张表
 */
@Data
@TableName("message")
public class Message {

    // 已读标记口径与 Notice 一致：DB 是 TINYINT + CHECK(0/1)，Java 侧也用 0/1 不用 boolean
    public static final int UNREAD = 0;
    public static final int READ = 1;

    // 正文长度上限，与 V5 的 content VARCHAR(500) 同口径；这里超限是用户输入问题，直接 400
    public static final int CONTENT_MAX_LENGTH = 500;

    // 对方回复前的"招呼"字数上限：短到只能开口、长到不能骚扰，前后端同口径
    public static final int FIRST_MSG_MAX_LENGTH = 30;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fromId;
    private Long toId;
    //私信上下文商品（发起入口带过来），可空；商品删除时 DB 侧 SET NULL，聊天记录保留
    private Long goodsId;
    private String content;
    //单行设计：一条消息只存一行，is_read 是「接收方」的已读态——
    //send() 落库恒为 UNREAD，接收方打开会话时 readConversation() 置 READ
    @TableField("is_read")
    private Integer isRead;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
