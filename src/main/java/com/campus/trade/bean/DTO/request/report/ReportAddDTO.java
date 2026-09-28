package com.campus.trade.bean.DTO.request.report;

import com.campus.trade.bean.entry.Report;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "提交举报参数")
@Data
public class ReportAddDTO {

    @NotBlank(message = "举报对象类型不能为空")
    @Schema(description = "被举报对象类型: goods-商品, comment-评论")
    private String targetType;

    @NotNull(message = "被举报对象id不能为空")
    @Schema(description = "被举报对象id")
    private Long targetId;

    //长度上限直接引用 Report 的常量（与 init.sql 的 reason VARCHAR(500) 同源），不抄数字
    @NotBlank(message = "举报理由不能为空")
    @Size(max = Report.REASON_MAX_LENGTH, message = "举报理由过长，请精简后再提交")
    @Schema(description = "举报理由")
    private String reason;
}
