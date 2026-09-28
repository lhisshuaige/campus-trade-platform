package com.campus.trade.bean.DTO.request.log;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Data
@Schema(description = "管理端操作日志查询参数")
public class OperLogQueryDTO {

    @Schema(description = "操作人id，传入则只看这个人的操作")
    private Long userId;

    @Schema(description = "操作描述关键字，模糊匹配 @Log 的 value")
    private String action;

    @Schema(description = "结果: 0-失败, 1-成功；不传为全部（排查事故时只看失败的那几条）")
    private Integer status;

    //审计问的永远是"那段时间"，所以时间范围是这张表最必要的过滤条件
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "开始时间(含)，格式 yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "结束时间(含)，格式 yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    @Min(value = 1, message = "页码最小为1")
    @Schema(description = "当前页码,默认为1")
    private Integer pageNum = 1;

    @Min(value = 1, message = "每页条数最小为1")
    @Max(value = 50, message = "每页条数最大为50")
    @Schema(description = "每页显示条数,默认为10")
    private Integer pageSize = 10;
}
