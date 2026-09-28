package com.campus.trade.bean.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 举报记录（管理端出参）。
 * 这是治理视图，所以带 reporter 的 userId —— 管理员要能查"这个人是不是在恶意刷举报"，
 * 面向 C 端的 VO 才一律不暴露内部标识（这里没有 C 端出参：普通用户提交后靠站内信拿结果）
 */
@Data
@Schema(description = "举报记录（管理端）")
public class ReportVo {

    @Schema(description = "举报id")
    private Long id;

    @Schema(description = "举报人id")
    private Long userId;

    @Schema(description = "举报人昵称")
    private String reporterNickname;

    @Schema(description = "被举报对象类型: goods/comment")
    private String targetType;

    @Schema(description = "被举报对象id")
    private Long targetId;

    //被举报内容的摘要：管理员在列表里就能判断要不要处置，不该为了看原文再跳两个接口
    @Schema(description = "被举报内容摘要（商品标题或评论正文，内容已删除时为占位文案）")
    private String targetSummary;

    @Schema(description = "举报理由")
    private String reason;

    @Schema(description = "处理状态: 0-待处理, 1-已处理, 2-已驳回")
    private Integer status;

    @Schema(description = "处理人id，未处理为 null")
    private Long handlerId;

    @Schema(description = "处理时间，未处理为 null")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime handleTime;

    @Schema(description = "举报时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
