-- =====================================================================
-- V10 · 楼中楼评论：comment 表加 parent_id / reply_to_user_id
--
-- 形态是「两层」而不是无限树：parent_id 永远指向【顶层评论】（写入侧归一化，
-- 回复一条回复时自动压平到它所在的楼），被回复的具体那一条用 reply_to_user_id 表达，
-- 「回复 @某人」的昵称读侧从 userId 现推、不做快照 —— 昵称改了展示跟着对。
--
-- 幂等守卫沿用 V3（information_schema.columns 查列）+ V4（table_constraints 查 CHECK）
-- + V7（statistics 查索引）的既有手法。
--
-- 刻意不加自引用 FK：本表是 @TableLogic 逻辑删、行物理常在，FK 对"父行是否有效"
-- 帮不上忙（deleted=1 的行仍然满足引用完整性），只会给治理脚本添堵。
-- 「parent 必须存在且同商品」由 CommentServiceImpl 在写入前校验。
--
-- CHECK 只锁住一个单行可判的不变量：reply_to_user_id 只允许出现在回复行上
-- （与 chk_user_phone/chk_user_email 同思路 —— 约束写"跨列的一致性"，不写"跨行的存在性"）。
-- 存量行两列全 NULL = 全是顶层，天然满足，校验不会顶死。
-- =====================================================================

-- ---------- parent_id：所在顶层评论 ID，NULL=顶层 ----------
SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'comment' AND column_name = 'parent_id') > 0,
    'SELECT ''comment.parent_id column already present''',
    'ALTER TABLE `comment` ADD COLUMN `parent_id` BIGINT NULL COMMENT ''所在顶层评论ID;NULL=顶层'' AFTER `user_id`'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- reply_to_user_id：被回复者（仅回复行有值） ----------
SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'comment' AND column_name = 'reply_to_user_id') > 0,
    'SELECT ''comment.reply_to_user_id column already present''',
    'ALTER TABLE `comment` ADD COLUMN `reply_to_user_id` BIGINT NULL COMMENT ''被回复的用户ID,仅回复行有值'' AFTER `parent_id`'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 展开整楼回复按 parent_id 过滤（回源缓存也吃这个索引） ----------
SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'comment' AND index_name = 'idx_comment_parent') > 0,
    'SELECT ''idx_comment_parent already present''',
    'ALTER TABLE `comment` ADD KEY `idx_comment_parent` (`parent_id`)'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- CHECK：只有回复能带被回复人 ----------
SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 'comment'
        AND constraint_name = 'chk_comment_reply_shape' AND constraint_type = 'CHECK') > 0,
    'SELECT ''chk_comment_reply_shape already present''',
    'ALTER TABLE `comment` ADD CONSTRAINT `chk_comment_reply_shape` CHECK (`reply_to_user_id` IS NULL OR `parent_id` IS NOT NULL)'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
