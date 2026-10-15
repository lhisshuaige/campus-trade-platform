-- =====================================================================
-- V4 · 存量库补齐 P2-2 / P2-3 的 CHECK 约束
--
-- 这一段对应 init.sql 里【存量库增量升级 · P2-2】那段注释掉的 ALTER。
-- 它们和 V3 的列/索引不同一类：V3 的动作**不可能让已有数据变非法**，
-- 而 ADD CONSTRAINT ... CHECK 会**校验存量数据**，有违反的行则整条 ALTER 直接失败。
-- 失败范围是好事：MySQL 8 的 DDL 是原子的，不会出现「加了两条、第三条没加」的半截状态。
--
-- 所以这条迁移在老库上失败的常见形态是：
--   ERROR 3819 / 4025: Check constraint 'chk_user_phone' is violated.
-- 这不是脚本写错了，是那台库上真有脏数据。先跑下面两条自检把它定位出来：
--   SELECT id, username FROM user
--     WHERE CHAR_LENGTH(username) < 3 OR CHAR_LENGTH(username) > 20;
--   SELECT id, username, phone FROM user
--     WHERE phone IS NOT NULL AND phone NOT REGEXP '^1[3-9][0-9]{9}$';
-- 自检查出来是空集的那些账号，可以把 phone 置 NULL（「没填」本来就是合法状态）再重跑迁移；
-- username 超长的没有好去处 —— 它得人工改名，因为改名会牵动 uk_username 与已发商品的归属显示。
--
-- ⚠ 前置条件：MySQL 8.0.16 起 CHECK 才真正生效（8.0.16 之前它会被解析后**静默丢弃**）。
--   库里少一条 CHECK 不会有任何报错，只会在有人绕过应用层写 SQL 时表现为「脏数据进来了」。
-- =====================================================================

-- ---------- user：长度、手机号格式、角色取值域 ----------
SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 'user'
        AND constraint_name = 'chk_user_username' AND constraint_type = 'CHECK') > 0,
    'SELECT ''chk_user_username already present''',
    'ALTER TABLE `user` ADD CONSTRAINT `chk_user_username` CHECK (CHAR_LENGTH(`username`) BETWEEN 3 AND 20)'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 'user'
        AND constraint_name = 'chk_user_phone' AND constraint_type = 'CHECK') > 0,
    'SELECT ''chk_user_phone already present''',
    'ALTER TABLE `user` ADD CONSTRAINT `chk_user_phone` CHECK (`phone` IS NULL OR `phone` REGEXP ''^1[3-9][0-9]{9}$'')'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 'user'
        AND constraint_name = 'chk_user_role' AND constraint_type = 'CHECK') > 0,
    'SELECT ''chk_user_role already present''',
    'ALTER TABLE `user` ADD CONSTRAINT `chk_user_role` CHECK (`role` IN (''user'', ''admin''))'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- goods：成色取值域、期望成交价不高于标价 ----------
-- 成色的白名单必须与 Goods.CONDITIONS 一致：Java 侧预检给人话提示，DB CHECK 兜住绕过应用层的写入。
-- 两处口径漂开时，报错形态是「统计里冒出第六种成色」而不是「接口挂了」，所以它必须有一条能被执行的定义
SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 'goods'
        AND constraint_name = 'chk_goods_condition' AND constraint_type = 'CHECK') > 0,
    'SELECT ''chk_goods_condition already present''',
    'ALTER TABLE `goods` ADD CONSTRAINT `chk_goods_condition` CHECK (`condition_level` IS NULL OR `condition_level` IN (''全新'',''几乎全新'',''轻微使用痕迹'',''明显使用痕迹'',''有瑕疵可正常使用''))'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 'goods'
        AND constraint_name = 'chk_goods_expect_price' AND constraint_type = 'CHECK') > 0,
    'SELECT ''chk_goods_expect_price already present''',
    'ALTER TABLE `goods` ADD CONSTRAINT `chk_goods_expect_price` CHECK (`expect_price` IS NULL OR (`expect_price` >= 0 AND `expect_price` <= `price`))'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 加约束前先确认这两列在（老库里可能整列缺失，那种情况连 V3 都补不了）----------
-- chk_goods_condition 引用 condition_level、chk_goods_expect_price 引用 expect_price。
-- 若上面两条报 1054 Unknown column，说明这库连 P2-1 的加列都没跑过，先按 V3 的手法把列补上：
--   ALTER TABLE goods ADD COLUMN condition_level VARCHAR(20) DEFAULT NULL COMMENT '成色';
--   ALTER TABLE goods ADD COLUMN expect_price  DECIMAL(10,2) DEFAULT NULL COMMENT '期望成交价';
--   ALTER TABLE goods ADD COLUMN trade_location VARCHAR(100) DEFAULT NULL COMMENT '交易地点';
--   ALTER TABLE goods ADD COLUMN contact        VARCHAR(100) DEFAULT NULL COMMENT '联系方式';
