package com.campus.trade.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志（管理端出参）。
 * 字段与 OperLog 一一对应，仍然单独建 VO 的原因和 report 一样：出参要能被 Schema 描述、要能改展示口径
 * 而不改动实体；params 在入库前就已脱敏，这里直接透出即可。
 */
@Data
@Schema(description = "操作日志（管理端）")
public class OperLogVo {

    @Schema(description = "日志id")
    private Long id;

    @Schema(description = "操作人id，登录/注册等无身份操作为 null")
    private Long userId;

    @Schema(description = "操作人用户名快照，账号注销后依然可读")
    private String username;

    @Schema(description = "操作描述")
    private String action;

    @Schema(description = "请求路径")
    private String requestUri;

    @Schema(description = "请求方式")
    private String httpMethod;

    @Schema(description = "目标类.方法")
    private String method;

    @Schema(description = "入参摘要（密码/token 等敏感字段已打码，超长已截断）")
    private String params;

    @Schema(description = "来源IP")
    private String ip;

    @Schema(description = "结果: 0-失败, 1-成功")
    private Integer status;

    @Schema(description = "失败原因，成功为 null")
    private String errorMsg;

    @Schema(description = "耗时(毫秒)")
    private Long costMs;

    @Schema(description = "操作时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
