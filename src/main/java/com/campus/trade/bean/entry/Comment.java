package com.campus.trade.bean.entry;

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
