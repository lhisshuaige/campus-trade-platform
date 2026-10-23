-- =====================================================================
-- V11 · comment 增加 reply_to_comment_id：记录"这条回复 @ 的是哪一行"
--
-- V10 只存了 reply_to_user_id（被回复人是谁），但"回楼主的主评论"和
-- "回楼主在楼里的某条回复"是同一个人 —— 只靠 userId 分不开这两类，
-- 而楼内排布恰恰要靠它分：回主评论的置顶第一组，接楼内话的跟在后面（抖音口径）。
--
-- 可空、不加索引（读侧从 comment:list 缓存行内存分节，不走 DB 查询）、
-- 不加 FK 不加 CHECK（跨行校验归 Service，与 parent_id 同一立场）。
-- 存量回复行为 NULL：读侧按「被 @ 人是不是楼主」保守归组 ——
-- 老数据里"@楼主"绝大多数就是回主评论，归第一组不冤。
-- =====================================================================

SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'comment' AND column_name = 'reply_to_comment_id') > 0,
    'SELECT ''comment.reply_to_comment_id column already present''',
    'ALTER TABLE `comment` ADD COLUMN `reply_to_comment_id` BIGINT NULL COMMENT ''被@的具体评论行id;NULL=存量未知'' AFTER `reply_to_user_id`'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
