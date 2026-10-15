package com.campus.trade.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 · 真实 MySQL 上验结构语义（需要 Docker）。
 *
 * <p><b>运行方式</b>：{@code ./mvnw -B -Pl3 test}。默认构建<b>不编译也不运行</b>这个文件
 * （pom 里 {@code **}{@code /*IT.java} 被 testExcludes 排掉了）——
 * 理由不是省事：Testcontainers 拉起容器时要连 Docker daemon，
 * 没装 Docker 的机器上它会以「环境缺失」的形式失败，
 * 而 CI 的 unit job 存在的意义恰恰是「任何环境都能给出的那批断言」。
 *
 * <p><b>诚实声明</b>：本文件在作者机器上只做到<b>编译通过</b>（{@code -Pl3 test-compile}），
 * <b>从未真正执行过</b>（本机没有 Docker）。第一次执行大概率要调整，
 * 常见的是镜像 tag 与 {@code withCommand} 参数拼写。所以别把它当成已经绿过的东西引用。
 *
 * <p><b>为什么非要用真的 MySQL，H2 不行</b>（README 第 5 节那条边界的具体化）：
 * <ol>
 *   <li>{@code MATCH ... AGAINST} + ngram 解析器：H2 没有全文索引，更没有 ngram；
 *       「二字词能不能命中」这件事只有在真实 MySQL 上才是可测的，
 *       而它正是 P3-2 唯一的验收标准，且失败形态是<b>静默返回 0 行</b>；</li>
 *   <li>{@code CHECK} 约束：8.0.16 之前 MySQL 会解析后<b>静默丢弃</b>，
 *       「约束存在」与「约束生效」是两件事，只有真库能验后者；</li>
 *   <li>{@code ON UPDATE CURRENT_TIMESTAMP}：MySQL 方言，H2 的等价写法行为不一致；</li>
 *   <li>迁移脚本里的 {@code information_schema} 判断 + {@code PREPARE/EXECUTE}：
 *       整段是 MySQL 系统视图语法，mock 不了。</li>
 * </ol>
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("L3 MySQL：迁移 + 中文全文检索 + 约束语义")
class MysqlMigrationAndFulltextIT {

    /**
     * 镜像可以整体换掉：Apple 芯片的机器上 mysql:8.0 的 arm64 变体偶发拉不动时，
     * 用 -Dit.mysql.image=mysql:8.0.37 之类钉一个确定有 arm64 的 tag。
     */
    private static final String MYSQL_IMAGE = System.getProperty("it.mysql.image", "mysql:8.0");

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(MYSQL_IMAGE)
                    // 这两个参数不是优化，是**功能开关**：
                    // ngram_token_size 默认 6，不传则 V2 会按设计主动失败（它去 SELECT 一张不存在的表），
                    // 于是这条 IT 同时也在验证「守卫本身是有效的」
                    .withCommand("--ngram_token_size=2", "--default-time-zone=+08:00");

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                // 测试里必须**关掉** baseline-on-migrate：主配置开它的目的是放过存量库，
                // 而这里要的正是在空库上把 V1~V4 完整跑一遍。带着它跑，
                // 会得到一个只有 flyway_schema_history 一行、九张表一张都没有的「成功」
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .load();
    }

    private static Connection conn() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** 迁移已经跑过就跳过 —— 让每个用例自己开连接，互不干扰 */
    private static synchronized void migrateOnce() {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.executeQuery("SELECT 1");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        if (!historyExists()) {
            flyway().migrate();
        }
    }

    private static boolean historyExists() {
        try (Connection c = conn();
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT COUNT(1) FROM information_schema.tables "
                             + "WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history'")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int count(String sql) {
        try (Connection c = conn();
             ResultSet rs = c.createStatement().executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static String scalar(String sql) {
        try (Connection c = conn();
             ResultSet rs = c.createStatement().executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** 造一条能过 FK 与 CHECK 的商品，返回 goods.id；用户名带后缀避免用例间撞 uk_username */
    private static long seedGoods(String suffix, String title) throws SQLException {
        exec("INSERT INTO user (username, password, role) VALUES ('it_user_" + suffix + "', 'x', 'user')");
        exec("INSERT INTO category (name, sort) VALUES ('IT分类" + suffix + "', 900) "
                + "ON DUPLICATE KEY UPDATE sort = VALUES(sort)");
        exec("INSERT INTO goods (user_id, category_id, title, price) "
                + "VALUES (LAST_INSERT_ID(), (SELECT id FROM category WHERE name='IT分类" + suffix + "'), "
                + "'" + title + "', 30.00)");
        // LAST_INSERT_ID() 已经被 category 的插入覆盖过，所以这里显式取回
        return Long.parseLong(scalar("SELECT MAX(id) FROM goods"));
    }

    @Test
    @Order(1)
    @DisplayName("V1~V4 能在空库上一次跑成，且第二次执行 0 个迁移")
    void migrateFromEmptyThenIdempotent() {
        // 这个用例必须是第一个跑：它要的是**真正空的库**。容器是 static 的（整个类共用一个，
        // 起 4 个 MySQL 容器会把这层测试变成几分钟的等待），所以一旦先跑了别的用例，
        // migrateOnce() 就已经把历史表建好了，“执行 4 个迁移”会变成 0
        assertFalse(historyExists(), "本用例假设库是空的；不是空的就是方法执行顺序被改了");
        Flyway fw = flyway();
        MigrateResult first = fw.migrate();
        assertTrue(first.success, "迁移结果必须是 success");
        assertEquals(4, first.migrationsExecuted, "空库上 V1~V4 应当全部执行");

        // 第二次是整条 L3 里最值钱的一步：迁移脚本里那些 information_schema 判断，
        // 只有在对象已存在的库上再跑一次才算被验证过（CI 的 migration job 同理）
        MigrateResult second = fw.migrate();
        assertEquals(0, second.migrationsExecuted, "重复执行不该再跑任何迁移");

        // 结构断言逐项来，不靠「看起来没报错」
        assertEquals(9, count("SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE() "
                + "AND table_name IN ('user','category','goods','comment','collect','order','notice','report','oper_log')"));
        assertEquals(6, count("SELECT COUNT(1) FROM category"), "分类种子数据应当在");
        // V3 的产物：单列老索引被联合索引取代，且没留下残骸
        assertEquals(1, count("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'goods' AND index_name = 'idx_goods_status_create'"));
        assertEquals(0, count("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'goods' AND index_name = 'idx_goods_status'"));
        // V2 的产物
        assertEquals(1, count("SELECT COUNT(1) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'goods' AND index_name = 'ft_goods_title'"));
    }

    @Test
    @DisplayName("中文双字关键词能命中 —— P3-2 真正的验收，失败形态是静默 0 行")
    void ngramFulltextMatchesTwoCharChineseKeyword() {
        migrateOnce();
        assertEquals("2", scalar("SELECT @@ngram_token_size"),
                "容器没带 --ngram_token_size=2，后面所有全文断言都没有意义");
        try {
            seedGoods("ft", "四级英语词汇 教材 二手");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        // 前缀通配 * 是 buildFulltextExpr 的规则之一，这里连它一起验
        assertTrue(count("SELECT COUNT(1) FROM goods WHERE MATCH(title) AGAINST('+教材*' IN BOOLEAN MODE)") >= 1,
                "ngram=2 时二字词必须命中");
        // 反向对照：不相关的词必须不命中，否则说明 MATCH 退化成了恒真
        assertEquals(0, count("SELECT COUNT(1) FROM goods WHERE MATCH(title) AGAINST('+冰箱*' IN BOOLEAN MODE)"));
    }

    @Test
    @DisplayName("CHECK 约束确实生效，而不只是被解析后丢弃")
    @SuppressWarnings("SqlNoDataSourceInspection")
    void checkConstraintsActuallyReject() {
        migrateOnce();
        // 「张三」两字：Java 侧 User.USERNAME_MIN_LENGTH=3 拦一次，DB 侧 chk_user_username 再拦一次。
        // RequestDtoValidationTest 已经把这个取值固定成断言，这里是它的库侧 counterpart ——
        // 两处都在，才算「口径只有一个，执行点有两个」
        assertThrows(SQLException.class,
                () -> exec("INSERT INTO user (username, password) VALUES ('张三', 'x')"),
                "chk_user_username 必须拦住两字用户名");
        assertThrows(SQLException.class,
                () -> exec("INSERT INTO user (username, password, phone) VALUES ('it_phone_bad', 'x', '12345')"),
                "chk_user_phone 必须拦住非 1[3-9] 开头的号码");
        assertThrows(SQLException.class,
                () -> exec("INSERT INTO user (username, password, role) VALUES ('it_role_bad', 'x', 'superuser')"),
                "chk_user_role 必须拦住第三种角色");

        try {
            seedGoods("chk", "可议价的小台灯");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThrows(SQLException.class,
                () -> exec("UPDATE goods SET expect_price = 50.00 WHERE title = '可议价的小台灯'"),
                "期望成交价高于标价是填反了，chk_goods_expect_price 必须拒");
        assertThrows(SQLException.class,
                () -> exec("UPDATE goods SET condition_level = '九成新' WHERE title = '可议价的小台灯'"),
                "成色第六种写法必须被拒（白名单与 Goods.CONDITIONS 同集）");
        // 正例也要有一条，否则「恒失败的约束」会伪装成「约束生效」
        try {
            exec("UPDATE goods SET expect_price = 25.00, condition_level = '轻微使用痕迹' "
                    + "WHERE title = '可议价的小台灯'");
        } catch (SQLException e) {
            throw new AssertionError("合法值不该被拒", e);
        }
        assertEquals(1, count("SELECT COUNT(1) FROM goods WHERE title = '可议价的小台灯' AND expect_price = 25.00"));
    }

    @Test
    @DisplayName("逻辑删除列与 ON UPDATE 语义都在（H2 复现不了这两件事）")
    void logicalDeleteColumnAndAutoUpdatedAtAreDefined() {
        migrateOnce();
        // @TableLogic 的列：MP 会自动追加 deleted=0，但手写 @Select 不会 ——
        // 于是「这一列存在」是 CollectMapper/StatisticsMapper 那些裸 SQL 的前提
        assertNotNullColumn("goods", "deleted");
        assertNotNullColumn("goods", "version");

        // ON UPDATE CURRENT_TIMESTAMP 是 MySQL 方言；这里验的是**定义**而不是行为，
        // 因为行为要 sleep 一秒以上才能观察，一个靠 sleep 通过的断言在 CI 上迟早变成 flaky
        String extra = scalar("SELECT EXTRA FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = 'order' AND column_name = 'update_time'");
        assertTrue(extra != null && extra.toLowerCase().contains("on update current_timestamp"),
                "order.update_time 必须让数据库自己刷新，而不是指望每个 Service 记得 setUpdateTime");
    }

    private static void assertNotNullColumn(String table, String column) {
        assertFalse(count("SELECT COUNT(1) FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = '" + table + "' AND column_name = '" + column + "'") == 0,
                table + "." + column + " 必须存在");
    }
}
