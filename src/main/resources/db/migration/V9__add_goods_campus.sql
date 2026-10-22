-- =====================================================================
-- V9 · goods 增加校区维度（黄家湖/青山两校区面交筛选）
--
-- 决策：校区不进分类树 —— 商品只有一个 category_id，校区若占用这个坑，
-- 「黄家湖的教材」就永远查不出来；分类管"是什么"，校区管"在哪面交"，正交维度各自成列。
--
-- 存量商品留 NULL（不为老数据编造校区），列表/展示侧对 NULL 不渲染徽章；
-- 新商品必填由 GoodsAddDTO 的 @NotBlank + checkGoodsContent 白名单在写入侧管，
-- 本 CHECK 允许 NULL 只兜"手工 SQL 写进第三种校区"这一类越界 ——
-- NULL 放过与 chk_user_phone/chk_user_email 完全同构。
--
-- 幂等手法沿用 V3 的加列守卫 + V4 的 CHECK 守卫（baseline 库可能两者都缺）。
-- =====================================================================

SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'goods' AND column_name = 'campus') > 0,
    'SELECT ''goods.campus column already present''',
    'ALTER TABLE `goods` ADD COLUMN `campus` VARCHAR(20) NULL COMMENT ''校区:黄家湖校区/青山校区,NULL=存量未标'' AFTER `trade_location`'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql = (SELECT IF(
    (SELECT COUNT(1) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 'goods'
        AND constraint_name = 'chk_goods_campus' AND constraint_type = 'CHECK') > 0,
    'SELECT ''chk_goods_campus already present''',
    'ALTER TABLE `goods` ADD CONSTRAINT `chk_goods_campus` CHECK (`campus` IS NULL OR `campus` IN (''黄家湖校区'', ''青山校区''))'));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
