-- =====================================================================
-- db/init.sql —— 已退役（P3-15）：现在它**只做一件事**，建库。
--
-- 为什么还要留着这个文件，而不是干脆删掉：
--   Flyway 不能替它做这件事。Flyway 的迁移连接已经落在 campus_trade 上，
--   而 JDBC 连不上一个不存在的库 —— 建库需要一次不带 database 段的连接。
--   把 CREATE DATABASE / USE 放进迁移脚本更糟：迁移必须能在任意库名（测试库、蓝绿库）下
--   原样执行，脚本里出现 USE 会让后面每条语句的 schema 语义取决于上一行，
--   而 Flyway 记录的却是「这条迁移已成功」。
--
-- 原来这里那份 286 行的建表脚本去哪了（286 = `git show HEAD:src/main/resources/db/init.sql` 的行数）：
--   · 9 张表 + 约束 + 索引 + 分类种子 → V1__baseline_schema.sql
--   · goods.title 的 ngram 全文索引   → V2__goods_title_fulltext.sql
--   · 存量库补 deleted / version / 联合索引 → V3__align_legacy_columns_and_indexes.sql
--   · 存量库补 P2-2 的 CHECK 约束      → V4__add_legacy_check_constraints.sql
--   目录：src/main/resources/db/migration/，应用启动时自动执行。
--   脚本里保留了原文件的关键结论（为什么列序不重要、为什么先建后删索引、
--   为什么 CHECK 会因存量脏数据失败、ngram_token_size 为什么必须在建索引前就是 2）。
--
-- 原来末尾那几段【存量库增量升级】的注释掉的 ALTER 去哪了：
--   变成 V2/V3/V4 里可执行且幂等的动态 SQL。它们此前是「靠人在部署时取消注释、
--   手工确认列是否已存在」的知识 —— 这类知识唯一的存储介质是部署者的记性。
--
-- 新库怎么起（三选一）：
--   A. 手工：先执行本文件，然后 mvnw spring-boot:run（Flyway 会把 V1~V4 跑完）
--   B. docker：docker compose up -d mysql（MYSQL_DATABASE=campus_trade 由镜像自己建库），
--      应用侧同样只剩 Flyway 建结构 —— 见 README
--   C. 已有库（此前用旧 init.sql 建过的）：什么都不用做。
--      application.yaml 里 baseline-on-migrate=true + baseline-version=4 会让 V1~V4 被跳过，
--      并把当前状态记入 flyway_schema_history，之后新增的 V5 起照常执行
-- =====================================================================

CREATE DATABASE IF NOT EXISTS campus_trade DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;

USE campus_trade;
