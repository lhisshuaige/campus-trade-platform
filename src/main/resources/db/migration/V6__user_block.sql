-- -----------------------------------------------------
-- 私信拉黑表 user_block：blocker 把 blocked 挡出自己的收件箱
--
-- 单向语义（与产品口径一致："拉黑之后 对方就无法继续进行私信"）：
--   blocked 给 blocker 发 → 拒；blocker 给 blocked 发 → 放行。
--   拉黑表达的是"不想收"，不是"替对方账号禁言自己"，
--   所以 blocker 反悔前想再联系对方也拦不住——前端拉黑确认后说明这一点。
--
-- 只拦"新的发送"，不删历史：拉黑前聊的内容双方都还看得见，
-- 解除拉黑不需要"恢复聊天记录"这种不可能兑现的承诺。
--
-- uk 兼当幂等闸门：应用层"先查再插"挡不住并发双击，唯一键兜底；
-- blocked_id 单独建索引是给 FK 的删除定位用的（uk 最左是 blocker，够不着）
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_block` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '拉黑记录ID',
    `blocker_id`  BIGINT   NOT NULL COMMENT '拉黑发起人ID',
    `blocked_id`  BIGINT   NOT NULL COMMENT '被拉黑用户ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '拉黑时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_block_pair` (`blocker_id`, `blocked_id`),
    KEY `idx_block_blocked` (`blocked_id`),
    CONSTRAINT `fk_block_blocker` FOREIGN KEY (`blocker_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_block_blocked` FOREIGN KEY (`blocked_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    -- 拉黑自己没有任何业务含义，Java 侧拦一手，这里拦住手工 SQL 的第二手
    CONSTRAINT `chk_block_not_self` CHECK (`blocker_id` <> `blocked_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '私信拉黑表';
