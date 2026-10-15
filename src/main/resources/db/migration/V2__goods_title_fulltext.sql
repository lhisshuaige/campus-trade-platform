-- =====================================================================
-- V2 · goods.title 的 ngram 全文索引（P3-2 的 DDL 出口）
--
-- 这条迁移存在的全部理由是「它必须同时对三种库成立」：
--   a) 空库（刚跑完 V1）              → 建索引
--   b) init.sql 建的老库、已有该索引   → 什么都不做（直接 ALTER 会 Duplicate key name 'ft_goods_title'）
--   c) init.sql 更早版本建的老库、缺它 → 补上，而不是让部署清单去记这段 DDL
-- MySQL 8 没有 CREATE INDEX IF NOT EXISTS，所以靠 information_schema 判断 + 动态 SQL。
-- 三段式（判断 → PREPARE → EXECUTE）在同一连接上执行，Flyway 一个迁移类用一个连接，会话变量不会丢。
--
-- ⚠ 为什么这条迁移要**主动让自已失败**：ngram_token_size 是服务端启动参数，
--   它在建索引那一刻被固化进倒排表。如果服务器是 3（或其他值）而这里是中文双字搜索的口径，
--   建出来的索引里根本没有二字词 —— 结果是「搜索永远返回空」，而且**不报错**。
--   一个静默的功能失效比一次部署失败危险得多（本项目在 fulltext-enabled 上已经做过同一次取舍，
--   见 application.yaml 的 search 段），所以这里选择用一条不存在的表名把问题顶出来。
-- =====================================================================

-- 已有该索引则跳过；否则按 token_size 决定「建」还是「炸」
SET @idx_exists = (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'goods' AND index_name = 'ft_goods_title'
);

-- MySQL 8.0.13 起 information_schema.global_variables 被移除，改读 performance_schema.global_variables。
-- 读不到（performance_schema 被关掉）时 @nts 为 NULL：那种情况下**选择继续建**而不是失败 ——
-- 拿不到证据不等于证据为坏，把「别人关了统计采集」变成「这个库不能部署」是过度反应。
SET @nts = (
    SELECT VARIABLE_VALUE FROM performance_schema.global_variables
    WHERE VARIABLE_NAME = 'ngram_token_size'
);

SET @ddl = IF(@idx_exists > 0,
    'SELECT ''ft_goods_title already present, skip''',
    IF(@nts IS NOT NULL AND @nts <> 2,
        'SELECT * FROM `__FLYWAY_ABORT_ngram_token_size_must_be_2_see_README`',
        'ALTER TABLE `goods` ADD FULLTEXT KEY `ft_goods_title` (`title`) WITH PARSER ngram'));

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- 留下三条人工排查用的只读语句（不在迁移里执行，出问题时对着看）：
--   SHOW VARIABLES LIKE 'ngram_token_size';
--     -- docker：在 my.cnf / 启动命令里加 --ngram_token_size=2，改完必须重启容器
--     -- 改完参数还要 **DROP 再重建**这个索引（或 OPTIMIZE TABLE），因为旧倒排表里的词长已经定了
--   SHOW INDEX FROM goods WHERE Key_name = 'ft_goods_title';
--   SELECT title FROM goods WHERE MATCH(title) AGAINST('+教材*' IN BOOLEAN MODE) LIMIT 5;
--
-- ⚠ 本条迁移不能修好「索引已存在但建的时候 token_size 是 3」这种库：
--   @idx_exists 判定为真就跳过了，而 information_schema 里查不到索引用的是哪个 parser。
--   这种库的症状是搜索恒空、无报错，唯一解法是人工：
--     ALTER TABLE goods DROP INDEX ft_goods_title;
--     -- 确认 ngram_token_size=2 并重跑本迁移（或直接手工 ADD）
-- ---------------------------------------------------------------------
