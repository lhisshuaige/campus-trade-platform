-- =====================================================================
-- V7 · 注册防恶意改造：user 表加 email 唯一锚点
--
-- 背景：注册唯一性此前只有 uk_username，脚本可以无限注册。改造后注册必须
-- 通过邮箱验证码（证明持有该邮箱），email 成为第二个唯一锚点。
--
-- 三段动作都照 V4 的幂等守卫手法（information_schema 查存在 + PREPARE/EXECUTE），
-- 因为老库里这列/索引/约束可能已有其一，重跑迁移要能安全跳过。
--
-- 老用户 email 全部留 NULL：MySQL 唯一索引允许多行 NULL，所以 uk_email
-- 不阻塞存量数据；「老用户补绑邮箱」不在本次范围（见 UserUpdateDTO 未动）。
--
-- chk_user_email 的「NULL 放过」与 Java 侧 @Pattern 对 null 跳过同口径，
-- 与 chk_user_phone 完全同构。正则里刻意不写 \d 和 \. 而是 [0-9]/[.]：
-- PREPARE 的字符串字面量会把 \. 的反斜杠吃掉（MySQL 对无法识别的转义符直接丢反斜杠），
-- 字符类里的 [.] 语义相同且不用跟两层转义较劲 —— V4 的 ^1[3-9][0-9]{9}$ 已是同款选择。
-- =====================================================================

-- ---------- 列：email VARCHAR(100) NULL ----------
SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'user' AND column_name = 'email') > 0,
    'SELECT ''user.email column already present''',
    'ALTER TABLE `user` ADD COLUMN `email` VARCHAR(100) NULL COMMENT ''注册邮箱(唯一锚点,存库前转小写)'' AFTER `phone`'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 唯一索引：uk_email（并发注册的最终裁判，与 uk_username 同角色） ----------
SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'user' AND index_name = 'uk_email') > 0,
    'SELECT ''uk_email index already present''',
    'ALTER TABLE `user` ADD UNIQUE KEY `uk_email` (`email`)'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- CHECK：与 User.EMAIL_REGEX 同一条口径 ----------
SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 'user'
        AND constraint_name = 'chk_user_email' AND constraint_type = 'CHECK') > 0,
    'SELECT ''chk_user_email already present''',
    'ALTER TABLE `user` ADD CONSTRAINT `chk_user_email` CHECK (`email` IS NULL OR `email` REGEXP ''^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}$'')'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
