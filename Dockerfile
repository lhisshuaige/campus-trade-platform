# =============================================================================
# CampusTrade 镜像构建
#
# 结构是「构建用 Maven 镜像 / 运行用 JRE 镜像」两段，理由不是省体积（虽然确实从
# 约 500MB 降到约 200MB），而是**运行镜像里没有 mvn、没有源码、没有 ~/.m2**：
# 容器被攻破时能做的事少一件是一件。
#
# 三处与本项目绑定的决定，每一处都对应一个不显眼但会咬人的故障：
#   1) 构建阶段跳过测试：本仓库的 L0/L1/L2 单测不依赖中间件（49 个用例、实测 12.7 秒），
#      但它们归 CI 的 unit job 跑。镜像构建里跑测试，等于把「这台构建机会不会连到 MySQL」
#      变成一个能影响出包的问题；而 @SpringBootTest 那个类默认 @Disabled，也帮不上忙。
#   2) TZ=Asia/Shanghai：app.collect-calibration.cron = "0 30 3 * * ?" 与
#      订单超时的时间口径都按**容器时区**解释。镜像默认 UTC，于是"每天 03:30 对账"
#      实际在北京时间 11:30 跑 —— 不报错、不失败，只是永远在错的时间做对的事。
#   3) ENTRYPOINT 里用 exec java：外层是一个 sh（为了展开 JAVA_OPTS），但它立刻被
#      exec 替换成 java，所以 java 就是 PID 1。docker stop 的 SIGTERM 能直达 JVM，
#      @PreDestroy（Redisson 客户端关闭、浏览量刷盘）才有机会执行。
#      不写 exec 则信号给了 shell、java 被 SIGKILL，
#      表现是每次发版都丢掉最后一批未落库的浏览量增量。
# =============================================================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 先只放依赖解析需要的三样：pom.xml 没变时这一层能命中缓存，
# 否则改一行业务代码就要重下 60 个依赖
COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw ./

# go-offline 只拉依赖；-Dmaven.repo.local 指到一个明确的目录，便于下一步 COPY 时不用猜路径
RUN ./mvnw -B -q dependency:go-offline

COPY src src
# 跳过测试的原因见文件头第 1 条；jar 名固定成 app.jar 是为了下面的 COPY 不受 version 变化影响
RUN ./mvnw -B -q -DskipTests package \
    && mv target/*.jar target/app.jar

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# 时区见文件头第 2 条。
# JAVA_OPTS 取容器内存上限的 75% 给堆：不写时 JVM 会按宿主机内存算默认堆，
# 配合 docker run --memory 就是「容器被 OOMKill 而 JVM 以为自己还很闲」。
# +ExitOnOutOfMemoryError：堆溢出后让进程干净退出，而不是带着半死的连接池继续接流量。
# 镜像里不带任何密钥：SPRING_PROFILES_ACTIVE 之外的 DB/Redis/MQ/JWT 变量都在运行时注入
# （名字与含义见 README 的配置表）
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=prod

# curl 只为 HEALTHCHECK 存在。这个基础镜像里没有 wget/curl，
# 而没有探针的镜像在 compose/k8s 里就是"进程活着 = 服务可用"那个错误等价的来源
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 非 root：默认这个镜像以 root 跑，而 /data/campus-trade/upload 是应用可写的目录 ——
# root + 应用可写目录 + 一个任意文件上传接口，是「上传接口变成写 /etc」的完整条件
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /data/campus-trade/upload /app/logs \
    && chown -R app:app /app /data /app/logs

COPY --from=builder --chown=app:app /build/target/app.jar app.jar

# 上传目录必须是卷：镜像是只读的、可被替换的，而商品图片是唯一删不掉的用户数据
# （compose 里对应 uploads 卷；不留卷的话，重新部署一次就把所有用户的图片清空）
VOLUME ["/data/campus-trade/upload", "/app/logs"]

EXPOSE 8080

USER app

# liveness 而不是 readiness：readiness 里含 db/redis/rabbit，中间件抖动会让 Docker
# 把**进程健康**的容器判成不健康并重启它（docker 本身重启策略 + health 状态会连锁），
# 而这类故障重启根本解决不了 —— 分组语义见 application.yaml 的 management 段
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=4 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/liveness || exit 1

# start-period 给 60s 的理由：Flyway + 连接池 + Redisson + MQ 声明式队列建好之前，
# liveness 不可能绿；这段时间内如果窗口太短，编排器会在应用起来之前就把容器杀掉
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
