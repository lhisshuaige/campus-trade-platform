package com.campus.trade.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 拉黑名单行：登录者视角的"我拉黑了谁"。
 * 只出现在 /message/blockedList（本人数据），不随会话/历史接口外泄，
 * 所以这里带全昵称头像是安全的
 */
@Data
public class BlockedUserVo {

    private Long userId;
    private String nickname;
    private String avatar;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
