package com.campus.trade.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理端评论治理返回。公开评论用的 CommentVo 按规范不含 userId/商品标题，" +
        "治理场景必须定位到人，所以单独一个 VO，而不是给公开 VO 加字段")
@Data
public class CommentAdminVo {

    @Schema(description = "评论id")
    private Long id;

    @Schema(description = "商品id")
    private Long goodsId;

    @Schema(description = "商品标题")
    private String goodsTitle;

    @Schema(description = "评论人id")
    private Long userId;

    @Schema(description = "评论人昵称")
    private String nickname;

    @Schema(description = "评论内容")
    private String content;

    /** 楼中楼：治理列表是扁平的（顶层与回复混排），靠这个字段认出"这是一条回复" */
    @Schema(description = "所在顶层评论id；null=顶层评论")
    private Long parentId;

    @Schema(description = "评论时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}