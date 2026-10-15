-- =====================================================================
-- V3 · 存量库结构对齐（P3-1 / P3-5 / P3-6 的 DDL 出口）
--
-- 这一段以前是 init.sql 末尾【存量库增量升级 · P3】那段**被注释掉的 ALTER**，
-- 靠人在部署时手工取消注释、手工确认列是否已存在。现在它是可执行且幂等的迁移：
-- 每个动作都先查 information_schema 再决定做不做，所以
--   · 新库（V1 已经建好这些列/索引）→ 全部跳过
--   · 老库（缺 deleted / version / 联合索引）→ 补齐
--   · 迁移重跑（脚本改动、校验失败后重来）→ 不会半途炸在 Duplicate column 上
--
-- ⚠ 顺序有两处是硬要求，写在下面的注释里而不是靠人记住：
--   1) 先 ADD idx_goods_status_create，再 DROP idx_goods_status ——
--      反过来会留出一个「status 查询无索引可用」的窗口，而主列表页就是那条 SQL；
--   2) ADD COLUMN 与 ADD INDEX 分在两条动态语句里执行，不合并成一条 ALTER ——
--      合并的话「列已在但索引缺」这种状态会让整条 ALTER 报 Duplicate column 而一同行不通。
--
-- ADD COLUMN 在 MySQL 8 是 INSTANT DDL（只改元数据，不重建表、不锁行），
-- 存量行自动拿到默认值 0，所以**不需要回填**：
-- 「以前没这列」与「这列是 0」在业务上是同一件事（未删除）。
-- 唯一不可逆的是旧数据：此前被物理删掉的评论/商品不会回来，痕迹从这一次以后才开始留下。
-- =====================================================================

-- ---------- goods.deleted ----------
SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'goods' AND column_name = 'deleted') > 0,
    'SELECT ''goods.deleted already present''',
    'ALTER TABLE `goods` ADD COLUMN `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除: 0-未删除, 1-已删除'''));
-- 刻意不写 AFTER view_count：列序对业务无意义，而 AFTER 会多一条前置假设
-- （老库里 view_count 本身也可能不存在），为了一个不存在的收益把迁移弄脆不值当
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- goods.version（乐观锁，只服务 updateGoods 那条整行覆盖路径）----------
SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'goods' AND column_name = 'version') > 0,
    'SELECT ''goods.version already present''',
    'ALTER TABLE `goods` ADD COLUMN `version` INT NOT NULL DEFAULT 0 COMMENT ''乐观锁版本号(整行覆盖式更新时比对)'''));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- comment.deleted（先有列，@TableLogic 才有的可写；缺它评论删除直接 500）----------
SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'comment' AND column_name = 'deleted') > 0,
    'SELECT ''comment.deleted already present''',
    'ALTER TABLE `comment` ADD COLUMN `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除: 0-未删除, 1-已删除'' AFTER `content`'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- goods (status, create_time) 联合索引：必须先建 ----------
SET @sql = (SELECT IF(
    (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'goods' AND index_name = 'idx_goods_status_create') > 0,
    'SELECT ''idx_goods_status_create already present''',
    'ALTER TABLE `goods` ADD INDEX `idx_goods_status_create` (`status`, `create_time`)'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 老的单列索引：后删。重复 ADD 不报错但会多一条同构索引，只多付写放大，所以两边都要判断 ----------
SET @sql = (SELECT IF(
    (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'goods' AND index_name = 'idx_goods_status') > 0,
    'ALTER TABLE `goods` DROP INDEX `idx_goods_status`',
    'SELECT ''idx_goods_status already dropped'''));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- notice / report / oper_log 三张表是老库里可能整张缺失的 ----------
-- 这三张的 CREATE TABLE IF NOT EXISTS 已经在 V1 里，所以这里不需要重复：
-- 被 baseline 跳过的老库里它们本来就存在，被真正执行的新库里 V1 刚建完。
-- 唯一剩下的边界：老库里表存在但**少一列**，那种情况属于「比 P3 更早的 init.sql 版本」，
-- 不在本迁移的覆盖范围内 —— 判断方法是跑一次 V1（把 baseline-version 调成 0），
-- 它带 IF NOT EXISTS，存在的表会被跳过、缺失的表会被建出来。

