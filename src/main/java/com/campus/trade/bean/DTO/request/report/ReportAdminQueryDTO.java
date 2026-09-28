package com.campus.trade.bean.DTO.request.report;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(description = "管理端举报查询参数")
public class ReportAdminQueryDTO {

    @Schema(description = "处理状态: 0-待处理, 1-已处理, 2-已驳回；不传为全部")
    private Integer status;

    @Schema(description = "被举报对象类型: goods/comment，不传为全部")
    private String targetType;

    @Schema(description = "举报人id，传入则只看这个人提交的举报")
    private Long userId;

    @Min(value = 1, message = "页码最小为1")
    @Schema(description = "当前页码,默认为1")
    private Integer pageNum = 1;

    @Min(value = 1, message = "每页条数最小为1")
    @Max(value = 50, message = "每页条数最大为50")
    @Schema(description = "每页显示条数,默认为10")
    private Integer pageSize = 10;
}
