-- =====================================================
-- 校园二手交易平台 CampusTrade 数据库初始化脚本
-- 包含：实体完整性、参照完整性、用户定义完整性约束
-- 适用：MySQL 8.x
-- =====================================================

CREATE DATABASE IF NOT EXISTS campus_trade DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;

USE campus_trade;

-- -----------------------------------------------------
-- 1. 用户表 user
-- 实体完整性：id 主键自增、username 唯一
-- 用户定义完整性：status 取值范围 0/1、role 默认 user
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`    VARCHAR(50)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(255) NOT NULL COMMENT '密码(BCrypt加密存储)',
    `nickname`    VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    `phone`       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `avatar`      VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    `role`        VARCHAR(20)  NOT NULL DEFAULT 'user' COMMENT '角色: user/admin',
    `status`      INT          NOT NULL DEFAULT 1 COMMENT '状态: 0-禁用, 1-正常',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    CONSTRAINT `chk_user_status` CHECK (`status` IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

-- -----------------------------------------------------
-- 2. 分类表 category
-- 实体完整性：id 主键自增
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `category` (
    `id`   BIGINT      NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    `name` VARCHAR(50) NOT NULL COMMENT '分类名称',
    `sort` INT         DEFAULT 0 COMMENT '排序值(越小越靠前)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_category_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品分类表';

-- -----------------------------------------------------
-- 3. 商品表 goods
-- 实体完整性：id 主键自增
-- 参照完整性：user_id 引用 user.id、category_id 引用 category.id
-- 用户定义完整性：price >= 0、status 取值 0/1/2
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `goods` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    `user_id`     BIGINT        NOT NULL COMMENT '发布者ID',
    `category_id` BIGINT        NOT NULL COMMENT '分类ID',
    `title`       VARCHAR(200)  NOT NULL COMMENT '商品标题',
    `price`       DECIMAL(10,2) NOT NULL COMMENT '价格',
    `image`       VARCHAR(500)  DEFAULT NULL COMMENT '图片URL',
    `description` TEXT          DEFAULT NULL COMMENT '商品描述',
    `status`      INT           NOT NULL DEFAULT 1 COMMENT '状态: 0-下架, 1-上架, 2-已售出',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    PRIMARY KEY (`id`),
    KEY `idx_goods_user` (`user_id`),
    KEY `idx_goods_category` (`category_id`),
    KEY `idx_goods_status` (`status`),
    CONSTRAINT `fk_goods_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_goods_category` FOREIGN KEY (`category_id`) REFERENCES `category` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_goods_price` CHECK (`price` >= 0),
    CONSTRAINT `chk_goods_status` CHECK (`status` IN (0, 1, 2))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品表';

-- -----------------------------------------------------
-- 4. 评论表 comment
-- 实体完整性：id 主键自增
-- 参照完整性：goods_id 引用 goods.id、user_id 引用 user.id
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `comment` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '评论ID',
    `goods_id`    BIGINT   NOT NULL COMMENT '商品ID',
    `user_id`     BIGINT   NOT NULL COMMENT '评论者ID',
    `content`     TEXT     NOT NULL COMMENT '评论内容',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评论时间',
    PRIMARY KEY (`id`),
    KEY `idx_comment_goods` (`goods_id`),
    KEY `idx_comment_user` (`user_id`),
    CONSTRAINT `fk_comment_goods` FOREIGN KEY (`goods_id`) REFERENCES `goods` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_comment_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '评论表';

-- -----------------------------------------------------
-- 5. 收藏表 collect
-- 实体完整性：id 主键自增
-- 参照完整性：goods_id 引用 goods.id、user_id 引用 user.id
-- 用户定义完整性：同一用户对同一商品只能收藏一次(唯一约束)
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `collect` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '收藏ID',
    `goods_id`    BIGINT   NOT NULL COMMENT '商品ID',
    `user_id`     BIGINT   NOT NULL COMMENT '收藏者ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_collect_user_goods` (`user_id`, `goods_id`),
    KEY `idx_collect_user` (`user_id`),
    KEY `idx_collect_goods` (`goods_id`),
    CONSTRAINT `fk_collect_goods` FOREIGN KEY (`goods_id`) REFERENCES `goods` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_collect_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '收藏表';

-- -----------------------------------------------------
-- 6. 订单表 order
-- 实体完整性：id 主键自增、order_no 唯一
-- 参照完整性：goods_id 引用 goods.id、seller_id/buyer_id 引用 user.id
-- 用户定义完整性：price >= 0、status 取值 0/1/2/3
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `order` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    `order_no`    VARCHAR(32)   NOT NULL COMMENT '订单编号',
    `goods_id`    BIGINT        NOT NULL COMMENT '商品ID',
    `seller_id`   BIGINT        NOT NULL COMMENT '卖家ID',
    `buyer_id`    BIGINT        NOT NULL COMMENT '买家ID',
    `price`       DECIMAL(10,2) NOT NULL COMMENT '成交价格',
    `status`      INT           NOT NULL DEFAULT 0 COMMENT '状态: 0-待确认, 1-已确认, 2-已完成, 3-已取消',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_order_seller` (`seller_id`),
    KEY `idx_order_buyer` (`buyer_id`),
    KEY `idx_order_goods` (`goods_id`),
    CONSTRAINT `fk_order_goods` FOREIGN KEY (`goods_id`) REFERENCES `goods` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_order_seller` FOREIGN KEY (`seller_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_order_buyer` FOREIGN KEY (`buyer_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_order_price` CHECK (`price` >= 0),
    CONSTRAINT `chk_order_status` CHECK (`status` IN (0, 1, 2, 3))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单表';

-- -----------------------------------------------------
-- 初始数据
-- -----------------------------------------------------
INSERT INTO `category` (`name`, `sort`) VALUES
('书籍教材', 1),
('电子设备', 2),
('生活用品', 3),
('服饰鞋帽', 4),
('运动器材', 5),
('其他', 6);
