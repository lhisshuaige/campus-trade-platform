package com.campus.trade;

import com.campus.trade.mapper.UserMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 全量上下文启动的冒烟测试（原有 {@code contextLoads}）。
 * <p>
 * 保留但<b>默认禁用</b>，原因不是它没用，而是它<b>不该在 {@code mvn test} 里跑</b>：
 * <ol>
 *   <li>{@code @SpringBootTest} 会真的连 MySQL / Redis / RabbitMQ —— 它测的是「本机三个中间件
 *       此刻都活着且配置没写错」，而不是「业务逻辑对不对」。CI 上没有人工会先启动这些，
 *       结果就是一个恒红的用例；红久了就没人看，反而把<b>真</b>问题的信号废掉了；</li>
 *   <li>它原来唯一的实质动作是「{@code new BCryptPasswordEncoder()} 编一次密、匹配一次」，
 *       这跟容器没关系 —— 那部分已经带着可验证的断言搬到
 *       {@link com.campus.trade.PasswordEncoderTest}（不启容器、0.1 秒级）；</li>
 *   <li>它当初一个断言都没有（只 {@code System.out.println}），也就是说<b>永远不会失败</b>。
 *       一个不可能失败的测试不是测试，是装饰。</li>
 * </ol>
 * 想验证「接线是否完整」（数据源、Mapper 扫描、拦截器、MQ 队列声明）时，手工跑一次：
 * <pre>mvn test -Dtest=CampusTradeApplicationTests -DfailIfNoTests=false</pre>
 * 带真实 DB 的持久层断言属于 P3-11 第三层（Testcontainers，需 Docker），
 * 不该由这个依赖本地环境的类代劳。
 */
@SpringBootTest
@Disabled("依赖本地 MySQL/Redis/RabbitMQ，CI 上必红；容器接线请手工跑，业务断言见 PasswordEncoderTest 等")
class CampusTradeApplicationTests {

    @Autowired
    private UserMapper userMapper;

    @Test
    @DisplayName("上下文能起来、Mapper 能被注入（结构性冒烟，不含业务断言）")
    void contextLoads() {
        // 这里必须是断言而不是 println： println 永远不会失败，也就永远不会保护任何东西
        org.junit.jupiter.api.Assertions.assertNotNull(userMapper,
                "UserMapper 没被扫描到 —— 检查 @MapperScan 与 mapper 包路径");
    }
}
