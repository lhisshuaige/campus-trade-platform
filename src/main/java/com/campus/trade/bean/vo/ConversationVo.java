package com.campus.trade.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表行：一行 = 一个聊天对象（对方信息 + 最后一条预览 + 未读数）。
 * 带 peerId 与 MessageVo 带 fromId/toId 同理：会话点开就是"和这个人聊"，
 * /chat/:peerId 没有别的途径拿到这个 id（VO 白名单规范的例外，理由在此写明）
 */
@Data
@Schema(description = "私信会话")
public class ConversationVo {

    @Schema(description = "对方用户id")
    private Long peerId;

    @Schema(description = "对方昵称")
    private String peerNickname;

    @Schema(description = "对方头像")
    private String peerAvatar;

    @Schema(description = "最后一条消息内容")
    private String lastContent;

    @Schema(description = "最后一条消息时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastTime;

    @Schema(description = "对方发给我的未读条数")
    private Long unreadCount;

    @Schema(description = "最后一条消息的关联商品id(可空,仅作展示上下文)")
    private Long goodsId;
}
