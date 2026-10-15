-- -----------------------------------------------------
-- 私信表 message（买家 ↔ 卖家围绕商品的一对一交流，用于议价/约地点）
-- 实体完整性：id 主键自增
-- 参照完整性：from_id/to_id 引用 user.id、goods_id 关联本次私信针对的商品
-- 用户定义完整性：is_read 只允许 0/1
--
-- 为什么单表、不建 conversation（会话）表：
--   会话只是「两个人 + 一个商品」的聚合视图，没有独立于消息的持久状态，
--   建表反而要维护「最后一条消息/未读数」这类派生列的一致性——读时用
--   (LEAST(from_id,to_id), GREATEST(...)) 配对 + 窗口函数聚合即可，无冗余。
--
-- 为什么保留 goods_id 且可空：
--   私信围绕商品发起，但会话是连续的——同一商品谈完可能继续问别的，
--   首条带 goods_id 定位上下文，后续可空；商品被删（CASCADE）不该销毁聊天记录，
--   所以这里用 SET NULL 而非 CASCADE：商品没了，历史对话仍有留存价值。
--
-- is_read 语义：这条消息「接收方」读过没有。发送方的消息恒为 1（自己发的当然看过）。
-- 未读角标 = COUNT(*) WHERE to_id=me AND is_read=0，与会话列表的 unread 同源。
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `message` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '私信ID',
    `from_id`     BIGINT       NOT NULL COMMENT '发送者ID',
    `to_id`       BIGINT       NOT NULL COMMENT '接收者ID',
    `goods_id`    BIGINT       DEFAULT NULL COMMENT '关联商品ID(私信上下文,商品删除后置空)',
    `content`     VARCHAR(500) NOT NULL COMMENT '消息正文',
    `is_read`     TINYINT      NOT NULL DEFAULT 0 COMMENT '接收方已读: 0-未读, 1-已读',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    PRIMARY KEY (`id`),
    -- 会话分页固定是「两人之间按 id 正序翻」，联合索引覆盖 from,to 双向需要两条：
    -- idx 给 (me→peer) 方向，另一方向靠 idx_message_to_unread 前缀 + 过滤，
    -- 个人维度消息量小，够用
    KEY `idx_msg_between` (`from_id`, `to_id`, `id`),
    KEY `idx_msg_to_read` (`to_id`, `is_read`, `id`),
    KEY `idx_msg_goods` (`goods_id`),
    CONSTRAINT `fk_msg_from` FOREIGN KEY (`from_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_msg_to` FOREIGN KEY (`to_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_msg_goods` FOREIGN KEY (`goods_id`) REFERENCES `goods` (`id`) ON DELETE SET NULL,
    CONSTRAINT `chk_msg_read` CHECK (`is_read` IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '私信表';
