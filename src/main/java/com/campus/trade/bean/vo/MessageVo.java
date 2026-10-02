package com.campus.trade.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 私信出参。与站内信 NoticeVo 不同，这里刻意带上 fromId/toId：
 * 聊天窗要按"这条是我发的还是对方发的"决定气泡左右，双方 id 是渲染必需，
 * 不是"多暴露一个内部标识"（VO 白名单规范的例外要写明理由，与 GoodsDetailVo.sellerId 同源）
 */
@Data
@Schema(description = "私信")
public class MessageVo {

    @Schema(description = "消息id")
    private Long id;

    @Schema(description = "发送者id")
    private Long fromId;

    @Schema(description = "接收者id")
    private Long toId;

    @Schema(description = "关联商品id(可空)")
    private Long goodsId;

    @Schema(description = "正文")
    private String content;

    @Schema(description = "接收方已读: 0-未读, 1-已读")
    private Integer isRead;

    @Schema(description = "发送时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
