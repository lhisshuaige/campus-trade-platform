package com.campus.trade.bean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 私信拉黑：一行 = "blocker 拉黑了 blocked"。
 * 现行语义是双向断开——拉黑后双方都发不出私信，blocker 想再联系须先取消拉黑
 * （V6 文件头注释写的是最初的单向口径：迁移已应用、checksum 不能改，以本类与 send() 为准）
 * (blocker_id, blocked_id) 唯一键由 V6 保证
 */
@Data
@TableName("user_block")
public class UserBlock {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long blockerId;
    private Long blockedId;
    private LocalDateTime createTime;
}
