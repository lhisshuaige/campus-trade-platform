package com.campus.trade.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内信出参。不带 userId：收件箱接口按登录态返回"你自己的"消息，
 * 每条都带同一个 userId 没有信息量，还多暴露一个内部标识（VO 白名单规范）
 */
@Data
@Schema(description = "站内信")
public class NoticeVo {

    @Schema(description = "消息id")
    private Long id;

    @Schema(description = "类型: order-交易, collect-收藏, comment-评论, system-系统通报")
    private String type;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "正文")
    private String content;

    @Schema(description = "关联业务id: 交易类是订单id，收藏/评论/系统类是商品id或举报id")
    private Long bizId;

    @Schema(description = "已读: 0-未读, 1-已读")
    private Integer isRead;

    @Schema(description = "产生时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
