package com.campus.trade.bean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("comment")
public class Comment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long goodsId;
    private Long userId;
    /** 所在【顶层评论】ID；NULL=顶层。写入侧已归一化（楼中楼只两层），永远指楼根而不是"回复的上一条" */
    private Long parentId;
    /** 被回复那条评论的作者；仅回复行有值（DB 有 chk_comment_reply_shape 兜住这个单行不变量） */
    private Long replyToUserId;
    /**
     * 被 @ 的【具体那一行】的 id；NULL=V11 之前的存量。
     * 和 replyToUserId 分开存，是因为"回楼主主评论"与"回楼主的楼内回复"是同一个人，
     * 只靠 userId 分不开 —— 而楼内排布（回主评论置顶、接楼内话靠后）全靠这一列判定
     */
    private Long replyToCommentId;
    private String content;
    /**
     * 逻辑删除位（@TableLogic）。评论是内容而不是关系行：“删掉”不该等于“销毁”。
     * 内容治理要能回答“这条违规内容当时写了什么”，而以前它是随 `fk_comment_goods`
     * 的 CASCADE 一起物理消失的，什么痕迹都留不下（见 P3-5 与 report 表的那段注释）。
     * CommentMapper 没有手写 SQL，所以本表不存在“裸 SQL 漏带 deleted=0”那个坑
     */
    @TableLogic
    private Integer deleted;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
