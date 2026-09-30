package com.campus.trade.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档元信息。
 *
 * 这个 bean 在 {@code knife4j.enable=false} 时根本不创建（正式环境关文档，见 application-prod.yaml）。
 * 光靠配置把 springdoc 关掉就已经拿不到数据，这里是把「文档对象」也一并撤掉，
 * 免得有人在别处注入 OpenAPI 时又把内容拼出来一半。
 */
@Configuration
@ConditionalOnProperty(name = "knife4j.enable", havingValue = "true", matchIfMissing = true)
public class Knife4jConfig {

    // 对外描述只写「这是什么系统 + 调用方必须知道的事」，不写「练手项目」：
    // 这句话会出现在 /doc.html 的第一行，是别人打开你接口文档时看到的第一段话
    private static final String DESCRIPTION = """
            校园闲置交易平台（C2C）后端 REST API。

            技术栈：Spring Boot 3 + MyBatis-Plus + MySQL 8 + Redis(降级/熔断/自愈) + RabbitMQ(订单超时)。

            认证：登录/注册与公开浏览接口（商品列表/详情、分类、评论列表、用户主页）匿名可调；
            其余接口需在请求头携带 Authorization: Bearer {token}；
            /admin/**、/statistics/**、/category 维护类接口要求 admin 角色。

            响应：统一为 MyResult{code, msg, data}，失败同时返回对应的 HTTP 状态码
            （400 参数/业务、401 未登录、403 无权限、404 不存在、413 上传超限、429 频繁、503 中间件不可用）。

            注意：文档只描述接口契约，能不能调通以服务端实际判定为准（拦截器与角色校验在文档之外，
            正式环境不开放本文档）。
            """;

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("校园闲置交易平台接口文档")
                        .description(DESCRIPTION)
                        // 与 pom 里的 0.0.1-SNAPSHOT 不是一回事：这一位是给调用方看的接口版本，
                        // 只在接口有不兼容变更时手动进位（0.0.1-SNAPSHOT 永远是 0.0.1-SNAPSHOT，写进文档没有信息量）
                        .version("1.0.0"));
    }
}
