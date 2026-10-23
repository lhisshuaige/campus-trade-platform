package com.campus.trade.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "返回给前端的数据，进行多表关联查询用户昵称")
public class CommentVo {
    @Schema(description = "评论id")
    private Long id;

    @Schema(description = "商品id")
    private Long goodsId;

    @Schema(description = "评论人id（点头像进TA的主页用）")
    private Long userId;

    @Schema(description = "评论人昵称")
    private String nickname;

    @Schema(description = "评论人头像URL(可能为空)")
    private String avatar;

    @Schema(description = "评论内容")
    private String content;

    @Schema(description = "所在顶层评论id；null=顶层评论")
    private Long parentId;

    /**
     * 「回复人 ▶ 被回复人」的右半，只在【接话组】给值（与楼内分节同一条判定 replySection）：
     * 回楼主主评论的行置顶在楼主正下方，位置即表意，不署名；
     * 接楼内任何一句话的（包括接"楼主在楼里的回复"——那虽是楼主，但属于接话）箭头照常。
     * 给值时对方昵称为空兜底"匿名用户"；顶层行与置顶组的回复行为 null
     */
    @Schema(description = "被回复人昵称(仅接楼内话的回复行有值)")
    private String replyToNickname;

    @Schema(description = "是否当前登录用户所发,用于前端判断是否展示删除按钮")
    private Boolean isOwner;

    /** 楼中楼预览：该楼排布（回楼主主评论一组置顶、接话组靠后，组内最新在前）的【首条】。
     *  getReplies 返回的回复行里为 null，回复不再带楼 —— 两层结构在出参形态上就被钉死 */
    @Schema(description = "楼内回复预览(仅顶层评论有值)")
    private java.util.List<CommentVo> replies;

    @Schema(description = "该楼回复总数(仅顶层评论有值;大于replies.size说明可展开)")
    private Integer replyCount;

    @Schema(description = "评论时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
