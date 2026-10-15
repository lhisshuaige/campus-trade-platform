# CampusTrade · 校园二手交易平台

 Spring Boot 3.1.5 / Java 17 / MyBatis-Plus 3.5.16 / MySQL 8 / Redis(Redisson) / RabbitMQ

面向校园内二手物品发布、搜索、议价、下单、内容治理的后端。业务上刻意保留了几件"二手交易特有的事"
（成色取值域、期望成交价、交易地点、逻辑删除而非物理删除），它们不是装饰，是这个项目和"通用商城 demo"的区别所在。

> 本仓库还有一份 [项目架构与面试速查.md](项目架构与面试速查.md)：
> 里面按"P0~P3 每一批做了什么、为什么这么做、不这么做会怎样"逐条记录。
> README 只负责**让你把它跑起来**，设计取舍不在这里重复。

---

## 1. 快速开始

三条路，按"你手上有什么"选。

### A. 只有 JDK 17，中间件都用 Docker（推荐）

```bash
# 1) 起 MySQL + Redis + RabbitMQ（MySQL 已带 ngram_token_size=2，见第 4 节）
docker compose up -d mysql redis rabbitmq

# 2) 生成本地配置（这个文件不入库，含你自己的密码）
cp src/main/resources/application-local.yaml.example src/main/resources/application-local.yaml
#   然后把里面的 <...> 换成真实值：MySQL 密码用 compose 里的 campus_dev_pwd，
#   Redis 无密码留空，MQ 用 guest/guest，jwt.secret 自己生成
openssl rand -base64 48        # 或者：把 compose 里生成的那个值复制过来

# 3) 起应用（Flyway 会自动把空库建成完整结构）
./mvnw spring-boot:run
```

接口文档：<http://localhost:8080/doc.html>（Knife4j，prod profile 下是关闭的）
健康探针：<http://localhost:8080/actuator/health/liveness>

### B. 本机已经装好 MySQL / Redis / RabbitMQ

只需要 `./mvnw spring-boot:run`，前提是第 2 步那个 `application-local.yaml` 存在且填对。
**没有这个文件时应用不会报"缺文件"，它会拿着空密码去连库**，
然后在 `Failed to configure a DataSource` 那里失败 —— 这是它最容易被误诊的一步，所以才要 `.example`。

库不存在时先建库（Flyway 不建库，它只能连一个已存在的库）：

```bash
mysql -uroot -p < src/main/resources/db/init.sql
```

### C. 只想跑测试，什么都不装

```bash
./mvnw test
```

49 个用例，不连 MySQL、不连 Redis、不起 Spring 容器。
`./mvnw -B clean test` 本地实测 12.7 秒（含两次编译），用例本身约 2.9 秒。详见第 5 节。

---

## 2. 环境变量（生产部署必须知道的全部项）

`application-prod.yaml` 是**入库的部署骨架**，只写开关和地址，不写任何密钥。所有敏感值都长这样：
`${DB_PASSWORD:}` —— 冒号后面是缺省值，敏感项的缺省一律是**空串**（连不上就立刻失败，
不会带着错密码反复重试）。

| 变量 | 用途 | 不配会怎样 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` 时加载部署骨架 | 默认 `local`，生产上拿到开发机的行为 |
| `DB_URL` | 数据源 URL | 落到 `localhost:3306/campus_trade`；compose/k8s 里连不上服务名 |
| `DB_USERNAME` / `DB_PASSWORD` | 库账号 | 密码空串 → 启动失败在 `flywayInitializer` |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | 缓存、分布式锁、限流、token 黑名单 | 默认值是**开发机的局域网 IP**，别人 clone 后连不上 |
| `RABBITMQ_HOST` / `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | 订单超时延迟取消 | 密码空串 → `ACCESS_REFUSED`，日志无限刷重连 |
| `MQ_LISTENER_AUTO_STARTUP` | 设 `false` 时消费者不启动 | 没有 MQ 的机器上会一直重试；关掉后订单超时改由定时扫描兜住（延迟变粗，功能不丢） |
| `JWT_SECRET` | token 签名密钥 | **必须给且必须 >= 256 bit**，否则第一次签发抛 `WeakKeyException`，症状是"登录接口 500" |
| `UPLOAD_PATH` | 图片落盘目录 | 默认是 Windows 路径写法，Linux 上无效 |
| `UPLOAD_BASE_URL` | 图片对外基地址 | 留空 = 从请求自身推断；套了反向代理后这个值会变成浏览器打不开的内部地址，**而且会写进 `goods.image` 让别的用户去加载** |
| `APP_CORS_ALLOWED_ORIGIN_PATTERNS` | 跨域白名单 | 缺省只放本机 = fail-closed：忘配时是"前端调不通、立刻被发现"，而不是"悄悄向全网开着" |
| `APP_SEARCH_FULLTEXT` | 中文标题搜索走 FULLTEXT 还是 LIKE | 见第 4 节 |
| `LOG_DIR` | logback 输出目录 | 默认 `logs/`；容器里必须指到挂载卷，否则实例销毁日志一起没 |

---

## 3. 数据库结构：Flyway，不要再用 init.sql

迁移脚本在 [`src/main/resources/db/migration/`](src/main/resources/db/migration/)，应用启动时自动执行：

| 版本 | 内容 | 为什么单独一层 |
|---|---|---|
| `V1__baseline_schema.sql` | 9 张表 + 约束 + 索引 + 分类种子数据 | `CREATE TABLE IF NOT EXISTS`；种子用 `ON DUPLICATE KEY UPDATE` 所以能重跑 |
| `V2__goods_title_fulltext.sql` | `goods.title` 的 ngram 全文索引 | 见第 4 节 |
| `V3__align_legacy_columns_and_indexes.sql` | 存量库补 `deleted` / `version` / 联合索引 | 原来注释在 init.sql 里靠人手工执行的那段 |
| `V4__add_legacy_check_constraints.sql` | 存量库补 P2 的 `CHECK` 约束 | 同上 |

`db/init.sql` 现在**只剩一条 `CREATE DATABASE`**（文件里有说明）。Flyway 不能替它做这件事：
迁移连接已经落在 `campus_trade` 上，而 JDBC 连不上一个不存在的库。

### 已经有一个用旧 init.sql 建好的库，怎么办

什么都不用做。`application.yaml` 里：

```yaml
spring.flyway.baseline-on-migrate: true
spring.flyway.baseline-version: 4
```

- **库非空**且没有 `flyway_schema_history` → Flyway 打一条 version 4 的基线，`V1~V4` 全部跳过，之后新增的 `V5` 起照常执行；
- **库是空的** → 不做 baseline，`V1~V4` 一口气跑完，得到一个与存量库同构的新库。

代价要写清楚：`baseline-version: 4` 是对"这个库真的到 V4 了"的**信任声明**。
一个缺 `oper_log` 的老库会被基线跳过，表永远不会被建出来，症状是运行时 500 而不是启动失败。
不信任存量库时：临时把 `baseline-on-migrate` 改成 `false` 跑一次（`V1` 会跳过已有表、补出缺失表），然后改回来。

> 本地实测记录：这个配置在作者已有的库上得到
> `Schema History table ... does not exist yet` → `Successfully baselined schema with version: 4`，
> 应用正常启动，业务接口不受影响。

---

## 4. 中文搜索（P3-2）：两个必须先满足的前提

商品标题搜索默认走 `MATCH(title) AGAINST(... IN BOOLEAN MODE)`，把 `LIKE '%kw%'` 的全表扫换成倒排索引查找。
它有两个硬前提，都不报错、只让结果变空：

1. **`ngram_token_size = 2` 必须在建索引之前就是 2。**
   它是服务端启动参数，而且在建索引那一刻被固化进倒排表。
   默认值 6 意味着索引里没有二字词 —— 中文双字关键词永远搜不到，而且不报错。
   改完参数还必须 `DROP INDEX` 再重建（或 `OPTIMIZE TABLE`），只改配置没有用。
   `docker-compose.yml` 与 CI 的 service 都已经带上这个参数。
2. **`ft_goods_title` 必须真的存在。**
   缺它而 `app.search.fulltext-enabled` 又开着时，搜索接口直接 500（MySQL 1191），
   而不是静默回退到 LIKE。这是**故意选的**：静默回退等于"上了全文索引"这件事永远没人发现。
   跑不了 DDL 的环境把 `APP_SEARCH_FULLTEXT=false` 翻成 LIKE 即可。

`V2` 里为此加了一道守卫：如果 `ngram_token_size` 不是 2，迁移会**主动失败**
（用一条不存在的表名把问题顶出来），而不是留下一个"建好了但搜不到"的索引。

关键词表达式的规则在 `GoodsServiceImpl.buildFulltextExpr`，它的行为由
[`GoodsFulltextExprTest`](src/test/java/com/campus/trade/service/impl/GoodsFulltextExprTest.java) 锁住，
包括几条实测数字（`+书` 命中 0 行、`+书*` 命中 625 行；`74-604` 这类含保留字符的关键词整条交回 LIKE）。

---

## 5. 测试：三层，按"需要什么环境"切

```
L0/L1/L2  ./mvnw test                        49 个用例，无容器、无 DB、无 Redis（实测 12.7 秒，含编译）
L3        ./mvnw -Pl3 test                   真实 MySQL（需要 Docker），验 FULLTEXT / CHECK / ON UPDATE 语义
```

L2 不起 Spring 容器，用 Mockito 验 Service 的**决策顺序**。这层存在理由是具体的：
本项目有几条安全语义不是「结果不对」而是「顺序不对」——
锁定判定必须在 BCrypt 比对之前、禁用状态只能在验对密码后被得知、
用户不存在与密码错误必须给同一句话。这些改动编译不报错、返回码也可能一样，
但安全性已经变了，而集成测试只能看到「最后返回了什么」。

L0/L1/L2 覆盖的东西（每个文件对应一组真实故障，不是覆盖率数字）：

| 文件 | 锁住什么 |
|---|---|
| `PasswordEncoderTest` | BCrypt 输出格式、同密码两次密文不同、错密码/空串不匹配 |
| `RequestDtoValidationTest` | 四层校验里 DTO 那一层的口径，**包含「某条规则故意不在这一层」的缺席断言** |
| `GoodsFulltextExprTest` | 全文表达式的 AND 前缀、通配后缀、保留字符回退 |
| `GoodsEnumAndLabelTest` | 状态标签/成色白名单与 DB 的 `CHECK` 同集，列宽装得下 |
| `RedisContentKeyTest` | Redis key 的物理拼法（改 key 会让线上存量缓存全体失效） |
| `UserServiceImplSecurityTest` | 上面那段「顺序」：锁定/比对/禁用的先后、失败计数只在验对密码后清零、改密只走那条原子 SQL 且进去的必须是编码值、禁用一定带 `evictAfterCommit` |

两个已知的诚实边界：

- **`CampusTradeApplicationTests` 默认 `@Disabled`。** 它是 `@SpringBootTest`，会真的连三个中间件，
  在 CI 上必红；红久了就没人看，反而把真问题的信号废掉。想验证接线完整性时手工跑：
  `./mvnw test -Dtest=CampusTradeApplicationTests`。
- **L3 不能用 H2 代替 MySQL。** H2 没有 `MATCH ... AGAINST`、没有 ngram、没有 `ON UPDATE CURRENT_TIMESTAMP`
  方言 —— 用它的"集成测试"恰好测不到本项目最需要测的那几件事。
  L3 在 `src/test/java/com/campus/trade/integration/` 下，文件名以 `IT` 结尾；
  默认构建**既不编译也不运行**它（pom 的 `testExcludes`），因为本机没 Docker 时它的失败形态是「环境缺失」，
  会污染「unit 是绿的」这条信号。要跑就 `./mvnw -B -Pl3 test`。
  相关用例带 `@Testcontainers(disabledWithoutDocker = true)`，没有 Docker 的机器上是**跳过**而不是失败。

---

## 6. 健康探针（P3-17）

| 路径 | 成员 | 失败意味着 |
|---|---|---|
| `/actuator/health/liveness` | `livenessState`, `ping` | 这个 JVM 救不回来 → 可以重启 |
| `/actuator/health/readiness` | `readinessState`, `db`, `redis`, `redisCircuit`, `rabbit`, `diskSpace` | 先别给它接流量，等它自己恢复 |

分组的唯一依据是"探针失败后编排器该做什么"。Redis/MQ 这类**可自愈**的降级只能进 readiness：
Redis 抖一下时把所有实例同时重启，重启完它们一起回头把 Redis 打成惊群 ——
把一个能降级的故障做成雪崩。

`redisCircuit`（`RedisCircuitHealthIndicator`）和 Boot 自带的 `redis` 不是重复：
`redis` 回答"Redis 此刻通不通"（发一条真 PING），`redisCircuit` 回答"**应用**现在怎么对待 Redis"
（开路期间缓存直接回源、下单直接 503，哪怕 PING 得通）。两者不一致的最长 30 秒窗口
正是"健康检查绿着但用户下不了单"的那个现象。

> ⚠ 探针必须同时从两条路上放行，少一条都是 401：
> `WebMvcConfig` 已 `exclude("/actuator/**")`（`JwtInterceptor` 拦的是 `/**`）；
> `SecurityConfig` 现在是 `anyRequest().permitAll()`。将来谁把鉴权收进 Security 链，
> 记得同时放行探针，否则症状是"容器反复重启"，而不是"探针不通"。

---

## 7. 目录结构

```
src/main/java/com/campus/trade/
  bean/
    dto/        request/{user,goods,order,comment,collect,report,notice,log,category} · result/MyResult
    entity/     9 张表的实体（常量与取值域就写在实体里，见 Goods/User）
    exception/  BusinessException · ErrorCode · GlobalExceptionHandler
    utils/      Redis*（缓存/锁/熔断/限流）· JwtUtils · ContentAuditUtils · 注解（@Log @RateLimit @RequireRole）
    vo/         出参视图
  config/       WebMvcConfig（4 个拦截器 + 静态资源）· CorsConfig · SecurityConfig · MybatisPlusConfig
                JwtInterceptor · RoleInterceptor · RateLimitInterceptor · OptionalAuthInterceptor
                OrderTimeoutMqConfig · RedissonClientHolder · MiddlewareHealthChecker · LogAspect
                RedisCircuitHealthIndicator · Knife4jConfig
  controller/   11 个
  mapper/       10 个
  service/      接口 · impl/（含定时任务、MQ 生产/消费、交易门面）
src/main/resources/
  application.yaml            主配置（含 Flyway、management 探针分组）
  application-local.yaml      本地私有值（gitignore，不入库）
  application-local.yaml.example   ↑ 的脱敏模板，新人 clone 后复制它
  application-prod.yaml       生产部署骨架（入库，只有开关与地址，没有密钥）
  logback-spring.xml          滚动文件 + 分环境级别
  db/init.sql                 只剩 CREATE DATABASE
  db/migration/V1..V4.sql     真正的结构定义
```

命名约定：`XxxServiceImpl`（不是 `XxxServiceImp`）、`service/impl`（不是 `service/imp`）、
`bean/entity`（不是 `bean/entry`）、`bean/dto`（小写，不是 `bean/DTO`）。

> 为什么专门提这一段：包名里的大写 `DTO` 在 Windows/macOS 上看不出问题，
> 但 `git` 在大小写不敏感的文件系统上**不会自动记录纯大小写改名** ——
> 结果是仓库里留着 `bean/DTO/Category.java` 而文件里写着 `package ...bean.dto;`，
> **任何 Linux CI 上 clone 下来直接编译失败**。这次已经用
> `git rm -r --cached bean/DTO` + `git add bean/dto` 修正过索引。

---

## 8. 已知边界（没做的事，以及为什么）

列在这里而不是不写，是因为"它还是个 demo"最常见的误判就来自这些缺口：

- **L3 已经写好但从未真实执行过**：`MysqlMigrationAndFulltextIT` 在本机只做到 `-Pl3 test-compile` 通过
  （作者机器没 Docker）。它是 CI 的 `integration` job 里第一次被跑，
  那个 job 目前带着 `continue-on-error: true` —— 它第一次绿过之后就应该把这个标记删掉，
  让它变成真正的门槛。在此之前，它的红不算构建失败，这一点不算隐瞒。
- **Dockerfile 与 docker-compose.yml 未经 `docker build` 验证**：作者机器上没有 Docker。
  CI 里只做了 `docker compose config` 语法校验，没有构建。第一次在有 Docker 的机器上用时要留意。
- **`/actuator/metrics`、`/env` 没有暴露**：只放 `health` 与 `info`，而 `info.env` 也关了。
  等有真实的指标消费方再开。
- **每个未登录请求会在 error 日志里落一整个 ERROR 级堆栈**：
  异常是在 `JwtInterceptor.preHandle` 里抛的，客户端能拿到正确的 `401 + {"code":401,...}`，
  但它同时被 Tomcat 记了一次。这条是加上 logback 之后才**看得见**的（不是新引入的）。
  影响是 error 日志会被扫描流量灌满，把真正的故障淹掉。
  修法有两个方向（拦截器自己写响应，或改成 `OncePerRequestFilter` 进 Security 链），
  都需要动鉴权路径，本次没有一并做掉。
- **没有 API 版本化**：URL 上没有 `/v1`。破坏性改动需要同时考虑前端。
- **没有真正的用户注销与数据保留策略**：`@TableLogic` 只保证"删了能查回来"。
