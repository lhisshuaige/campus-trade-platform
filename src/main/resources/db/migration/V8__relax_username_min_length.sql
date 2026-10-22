-- =====================================================================
-- V8 · 放开用户名长度下限（保留上限 20）
--
-- 背景：chk_user_username 原本是 CHAR_LENGTH BETWEEN 3 AND 20，两字实名「张三」被误伤
-- （RequestDtoValidationTest 里那条 known-issue 断言就是当初发现它的地方）。
-- 2026-10-20 产品决策：只卡上限、不要下限。@NotBlank 保证至少 1 个非空白字符。
--
-- MySQL 不支持 ALTER CONSTRAINT 就地改定义，只能 DROP 再 ADD；
-- DROP 侧用 V4 同款 information_schema 守卫（baseline 库可能没有这条约束）。
-- 重新 ADD 会校验存量行，但存量行原本就满足 >=3，必然也满足 <=20，不会像 V4 那样
-- 被脏数据顶死 —— 这条迁移没有已知的失败形态。
-- =====================================================================

SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 'user'
        AND constraint_name = 'chk_user_username' AND constraint_type = 'CHECK') > 0,
    'ALTER TABLE `user` DROP CONSTRAINT `chk_user_username`',
    'SELECT ''chk_user_username absent, nothing to drop'''));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 'user'
        AND constraint_name = 'chk_user_username' AND constraint_type = 'CHECK') > 0,
    'SELECT ''chk_user_username already re-added''',
    'ALTER TABLE `user` ADD CONSTRAINT `chk_user_username` CHECK (CHAR_LENGTH(`username`) <= 20)'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
