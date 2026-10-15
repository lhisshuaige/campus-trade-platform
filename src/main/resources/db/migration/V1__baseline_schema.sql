-- =====================================================================
-- V1 · 基线结构（表 + 约束 + 索引 + 分类种子数据）
--
-- 来源：原 db/init.sql 的建表段。与它有三处不同，每处都是有意的：
--   1) 去掉 CREATE DATABASE / USE —— Flyway 的连接已经落在 campus_trade 上，
--      迁移脚本里出现 USE 会让后续语句的 schema 语义变得不可控，
--      而且迁移必须能在任何库名（测试库、蓝绿库）下原样执行。
--      建库这一步留在 db/init.sql（现在只剩这一件事）。
--   2) goods 上**不建** ft_goods_title：全文索引单独走 V2。
--      理由不是分类好看，而是 V2 要做成「有条件的」，
--      这样存量库（已经有这个索引）不会撞 Duplicate key name，
--      缺这个索引的老库又能补上 —— 建在 CREATE TABLE 里就失去了这个复用能力。
--   3) 种子数据加 ON DUPLICATE KEY UPDATE：重复执行不报错，
--      而且管理员在后台改过的分类名/排序不会被一次迁移悄悄回滚掉。
--
-- 所有 CREATE TABLE 都带 IF NOT EXISTS：本地库是 init.sql 手工建的，
-- application.yaml 里 baseline-on-migrate=true + baseline-version=4 会让 V1~V4 全部被跳过；
-- 留着 IF NOT EXISTS 是为了「baseline 判断为不需要跳过」的那些半截状态不至于直接失败：
--      不信任存量库时把 baseline-on-migrate 临时改成 false 跑一次，靠的就是这些 IF NOT EXISTS
--      去「跳过已有的表、只建出缺的表」（本地已实测：空库跑完 V1~V4 与存量库结构逐项同构）。
-- =====================================================================

-- -----------------------------------------------------
-- 1. 用户表 user
-- 实体完整性：id 主键自增、username 唯一
-- 用户定义完整性：status 取值 0/1、username 长度、phone 格式、role 取值域
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
    `token_version` INT        NOT NULL DEFAULT 1 COMMENT 'Token版本号: 改密+1使旧Token失效',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    CONSTRAINT `chk_user_status` CHECK (`status` IN (0, 1)),
    -- 长度下限是业务口径，VARCHAR(50) 只是物理上限，所以这条 CHECK 提供的是列宽给不了的信息。
    -- 口径与 User.USERNAME_MIN/MAX_LENGTH 同源；字符集白名单只在 Java 侧校（User.USERNAME_REGEX），
    -- 因为 MySQL 的 REGEXP 不认 \u4e00 这类 Unicode 转义 —— 两侧管的事不同，不是同一句话抄两遍
    CONSTRAINT `chk_user_username` CHECK (CHAR_LENGTH(`username`) BETWEEN 3 AND 20),
    -- 手机号要么不填(NULL)，要么必须是大陆号码格式：与 User.PHONE_REGEX 等价
    -- （@Pattern 对 null 跳过、DB 侧 IS NULL 放过，两侧对"没填"的判断一致；空串两侧都判非法，
    --   所以"没填"只有一种表示法，统计未填手机号不需要写 IS NULL OR = ''）
    CONSTRAINT `chk_user_phone` CHECK (`phone` IS NULL OR `phone` REGEXP '^1[3-9][0-9]{9}$'),
    -- 角色取值域由 Role 枚举定义（User.ROLES 派生），写在这里是为了拦住手工 SQL 塞进来的第三种角色
    CONSTRAINT `chk_user_role` CHECK (`role` IN ('user', 'admin'))
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
-- 用户定义完整性：price >= 0、status 取值 0/1/2、成色取值域、期望成交价 <= 标价
-- 二手交易关键字段（成色/交易地点/联系方式/期望成交价）不是"可选装饰"：
-- 买家判断"这个价买不买得值"靠的就是这几项，缺了它们这个平台只能当新品商城用
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `goods` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '商品ID',
    `user_id`     BIGINT        NOT NULL COMMENT '发布者ID',
    `category_id` BIGINT        NOT NULL COMMENT '分类ID',
    `title`       VARCHAR(200)  NOT NULL COMMENT '商品标题',
    `price`       DECIMAL(10,2) NOT NULL COMMENT '价格(标价)',
    `expect_price` DECIMAL(10,2) DEFAULT NULL COMMENT '期望成交价(可议价底线),不高于标价',
    `condition_level` VARCHAR(20) DEFAULT NULL COMMENT '成色:全新/几乎全新/轻微使用痕迹/明显使用痕迹/有瑕疵可正常使用',
    `trade_location` VARCHAR(100) DEFAULT NULL COMMENT '交易地点(如:东三门/3号宿舍楼下)',
    `contact`     VARCHAR(100)  DEFAULT NULL COMMENT '发布者主动公开的联系方式(微信/QQ/手机号),仅登录用户可见',
    `image`       VARCHAR(500)  DEFAULT NULL COMMENT '图片URL',
    `description` TEXT          DEFAULT NULL COMMENT '商品描述',
    `status`      INT           NOT NULL DEFAULT 1 COMMENT '状态: 0-下架, 1-上架, 2-已售出',
    `collect_count` INT         NOT NULL DEFAULT 0 COMMENT '收藏数(冗余计数)',
    `view_count`  INT           NOT NULL DEFAULT 0 COMMENT '浏览量(DB存量,实时值=本列+Redis未落库增量)',
    -- 逻辑删除位：@TableLogic 的值列（0 正常 / 1 已删）。MP 自动给生成的 SQL 追加 deleted=0，
    -- 但【手写 @Select 不会】，所以 CollectMapper/StatisticsMapper/GoodsMapper 里凡是碰 goods 的裸 SQL 都得自己带上
    `deleted`     TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-未删除, 1-已删除',
    -- 乐观锁版本号：只服务"读整行→改几个字段→写回整行"那一条路径(updateGoods)，
    -- 状态迁移仍走单列 CAS，不给每张表都补一列（见 Goods.version 的注释）
    `version`     INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号(整行覆盖式更新时比对)',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    PRIMARY KEY (`id`),
    KEY `idx_goods_user` (`user_id`),
    KEY `idx_goods_category` (`category_id`),
    -- 主列表/我的商品/管理端列表共用同一条 SQL 形状：WHERE status=? ORDER BY create_time DESC。
    -- 原来的单列 idx_goods_status 只能过滤不能排序，仍要 filesort；联合索引第二列把排序一起吃掉，
    -- 而"只按 status 筛"走它的最左前缀等价于原索引，所以单列那条直接删掉，不留在表里白白多一次写放大
    KEY `idx_goods_status_create` (`status`, `create_time`),
    -- 中文标题搜索的 ngram 全文索引不在这里建，见 V2（要能幂等地补到存量库上）
    CONSTRAINT `fk_goods_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_goods_category` FOREIGN KEY (`category_id`) REFERENCES `category` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_goods_price` CHECK (`price` >= 0),
    CONSTRAINT `chk_goods_status` CHECK (`status` IN (0, 1, 2)),
    -- 成色不让自由填字符串："九成新"/"9成新"/"95新" 三种写法会把统计拆成三堆
    -- 取值域必须与 Goods.CONDITIONS 一致（Java 侧预检给人话，DB CHECK 兜住绕过应用层的写入）
    CONSTRAINT `chk_goods_condition` CHECK (
        `condition_level` IS NULL OR `condition_level` IN
        ('全新', '几乎全新', '轻微使用痕迹', '明显使用痕迹', '有瑕疵可正常使用')),
    -- 期望成交价高于标价是逻辑错误（标价 100、底线 200 不是"可议价"，是填反了）
    CONSTRAINT `chk_goods_expect_price` CHECK (
        `expect_price` IS NULL OR (`expect_price` >= 0 AND `expect_price` <= `price`))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '商品表';

-- -----------------------------------------------------
-- 4. 评论表 comment
-- 实体完整性：id 主键自增
-- 参照完整性：goods_id 引用 goods.id、user_id 引用 user.id
-- 逻辑删除：评论是内容而不是关系行，"删掉"与"销毁"不该是同一件事 —— 内容治理要能回答
-- "这条违规内容当时写了什么"，所以这里用 deleted 标记而不是真删（见 P3-5）
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `comment` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '评论ID',
    `goods_id`    BIGINT   NOT NULL COMMENT '商品ID',
    `user_id`     BIGINT   NOT NULL COMMENT '评论者ID',
    `content`     TEXT     NOT NULL COMMENT '评论内容',
    `deleted`     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0-未删除, 1-已删除',
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
    KEY `idx_order_status_create` (`status`, `create_time`),
    CONSTRAINT `fk_order_goods` FOREIGN KEY (`goods_id`) REFERENCES `goods` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_order_seller` FOREIGN KEY (`seller_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `fk_order_buyer` FOREIGN KEY (`buyer_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT,
    CONSTRAINT `chk_order_price` CHECK (`price` >= 0),
    CONSTRAINT `chk_order_status` CHECK (`status` IN (0, 1, 2, 3))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单表';

-- -----------------------------------------------------
-- 7. 站内信表 notice（消息通知：下单、对方确认、被收藏、被评论）
-- 实体完整性：id 主键自增
-- 参照完整性：user_id 引用 user.id（接收者注销，其收件箱随之消失，没有归属者的消息是垃圾数据）
-- 用户定义完整性：is_read 只允许 0/1
-- biz_id 是"多态关联"：type=order 时指向 order.id，type=goods 时指向 goods.id。
-- 多态关联不能建外键（一个列不能同时引用两张表），所以允许悬空：
-- 商品被删后这条"有人收藏了你的商品"仍然读得通，因为文案里已带商品标题快照
-- （站内信的文案是"当时说了什么"，不是"现在那个商品叫什么"）
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `notice` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `user_id`     BIGINT       NOT NULL COMMENT '接收者ID',
    `type`        VARCHAR(20)  NOT NULL COMMENT '类型: order-交易, collect-收藏, comment-评论, system-系统通报',
    `title`       VARCHAR(100) NOT NULL COMMENT '标题(前端列表直接展示)',
    `content`     VARCHAR(500) NOT NULL COMMENT '正文(含商品标题等快照文案,不依赖实时的业务行)',
    `biz_id`      BIGINT       DEFAULT NULL COMMENT '关联业务ID(订单id或商品id,由 type 决定语义)',
    `is_read`     TINYINT      NOT NULL DEFAULT 0 COMMENT '已读: 0-未读, 1-已读',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '产生时间',
    PRIMARY KEY (`id`),
    -- 收件箱查询固定是"某人的消息按时间倒序"，未读角标再加一列过滤，所以做联合索引
    KEY `idx_notice_user_read` (`user_id`, `is_read`, `create_time`),
    CONSTRAINT `fk_notice_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `chk_notice_read` CHECK (`is_read` IN (0, 1)),
    -- system 是给"不是互动但必须告知"的机器消息留的口子（如举报处理结果、账号被处置）
    CONSTRAINT `chk_notice_type` CHECK (`type` IN ('order', 'collect', 'comment', 'system'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '站内信表';

-- -----------------------------------------------------
-- 8. 举报表 report（内容治理的入口：用户发现违规 → 管理员处置）
-- 实体完整性：id 主键自增；同一用户对同一目标只留一条（防重复提交刷屏，见 Service 预检）
-- 参照完整性：user_id 引用 user.id；target_id 是多态关联（goods/comment），不建外键
-- 用户定义完整性：status 取值 0/1/2、target_type 取值 goods/comment
-- 为什么 target_id 不设 FK：举报的评论可能已被管理员删除，删掉举报记录等于销毁治理证据；
-- 留着悬空的 target_id 才能回答"这条违规内容当时被举报过"（这也正是 P3-5 想上 @TableLogic 的原因）
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `report` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '举报ID',
    `user_id`     BIGINT       NOT NULL COMMENT '举报人ID',
    `target_type` VARCHAR(20)  NOT NULL COMMENT '被举报对象类型: goods-商品, comment-评论',
    `target_id`   BIGINT       NOT NULL COMMENT '被举报对象ID(多态,由 target_type 决定)',
    `reason`      VARCHAR(500) NOT NULL COMMENT '举报理由',
    `status`      INT          NOT NULL DEFAULT 0 COMMENT '处理状态: 0-待处理, 1-已处理, 2-已驳回',
    `handler_id`  BIGINT       DEFAULT NULL COMMENT '处理人(管理员)ID,未处理为 NULL',
    `handle_time` DATETIME     DEFAULT NULL COMMENT '处理时间,未处理为 NULL',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '举报时间',
    PRIMARY KEY (`id`),
    -- 后台待办列表就是"按状态筛 + 按时间倒序"，与分析类查询同一套索引思路
    KEY `idx_report_status_create` (`status`, `create_time`),
    KEY `idx_report_target` (`target_type`, `target_id`),
    CONSTRAINT `fk_report_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    CONSTRAINT `chk_report_status` CHECK (`status` IN (0, 1, 2)),
    CONSTRAINT `chk_report_target_type` CHECK (`target_type` IN ('goods', 'comment'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '内容举报表';

-- -----------------------------------------------------
-- 9. 操作日志表 oper_log（原来只 log.info 落文件：出事后 grep 不动、查不到、统计不了）
-- 实体完整性：id 主键自增
-- 参照完整性：刻意不建 FK —— user_id 只是线索，username 是快照，
--   建了 FK + CASCADE 会变成"注销用户 = 销毁他的操作痕迹"，审计日志最不能接受这件事
-- 用户定义完整性：status 取值 0/1
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS `oper_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    `user_id`     BIGINT       DEFAULT NULL COMMENT '操作人ID(登录接口失败时为 NULL,因为还没解析出身份)',
    `username`    VARCHAR(50)  DEFAULT NULL COMMENT '操作人用户名快照(不依赖 user 表存在)',
    `action`      VARCHAR(50)  NOT NULL COMMENT '操作描述(@Log 注解的 value)',
    `request_uri` VARCHAR(200) DEFAULT NULL COMMENT '请求路径',
    `http_method` VARCHAR(10)  DEFAULT NULL COMMENT '请求方式',
    `method`      VARCHAR(200) NOT NULL COMMENT '目标类.方法',
    `params`      TEXT         DEFAULT NULL COMMENT '入参摘要(敏感字段已打码,超长截断)',
    `ip`          VARCHAR(64)  DEFAULT NULL COMMENT '来源IP(优先 X-Forwarded-For)',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '结果: 0-失败, 1-成功',
    `error_msg`   VARCHAR(500) DEFAULT NULL COMMENT '失败原因(业务提示语,不含堆栈与内部细节)',
    `cost_ms`     BIGINT       DEFAULT NULL COMMENT '耗时(毫秒)',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (`id`),
    KEY `idx_oper_log_user_time` (`user_id`, `create_time`),
    KEY `idx_oper_log_status_time` (`status`, `create_time`),
    CONSTRAINT `chk_oper_log_status` CHECK (`status` IN (0, 1))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '操作日志表';

-- -----------------------------------------------------
-- 分类种子数据
-- 幂等写法（原来是裸 INSERT，重复执行报 1062；迁移脚本必须能安全重跑）：
-- name 上有 uk_category_name，所以用自然键做冲突判定。
-- sort 用新值覆盖回默认值，但**不覆盖 name 之外管理员自己新增的分类** ——
-- ON DUPLICATE 只命中这 6 行，管理员后加的「乐器」这类不会被动到。
-- 需要提醒的一点：这 6 个分类的 sort 如果被管理员在后台改过，重跑迁移会把它改回来。
-- 种子数据与用户数据撞在同一张表上，这是「演示级种子」的固有代价，
-- 真要区分就得给 category 加一列 is_builtin —— 当前没有读侧需要它，所以不做
--
-- ⚠ 为什么是 `AS seed` 而不是传统的 `VALUES(sort)`：后者从 MySQL 8.0.20 起是
-- **deprecated（Error 1287）**，空库跑 V1 时 Flyway 会把这条警告原样打进日志（本地实测见到才算数）。
-- 它现在还能跑，但把一个「未来版本会删掉的写法」留在迁移脚本里，等于给未来的 MySQL 升级埋一颗雷。
-- 代价要写清：行别名写法要求 **MySQL >= 8.0.19**（版本下界比原来高了），docker-compose 与 CI 用的
-- `mysql:8.0` 都满足；真要在更老的库上跑，把这一行改回 `VALUES(`sort`)` 即可，语义完全相同
-- -----------------------------------------------------
INSERT INTO `category` (`name`, `sort`) VALUES
('书籍教材', 1),
('电子设备', 2),
('生活用品', 3),
('服饰鞋帽', 4),
('运动器材', 5),
('其他', 6)
AS `seed`
ON DUPLICATE KEY UPDATE `sort` = `seed`.`sort`;

