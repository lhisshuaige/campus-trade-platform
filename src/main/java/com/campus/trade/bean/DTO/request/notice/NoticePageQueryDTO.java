package com.campus.trade.bean.DTO.request.notice;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(description = "站内信分页查询参数")
public class NoticePageQueryDTO {

    //只看未读是收件箱最常用的筛选，做成布尔而不是让前端传 isRead=0：
    //内部存储口径（0/1）不该泄漏到接口契约里
    @Schema(description = "是否只看未读，默认全部")
    private Boolean unreadOnly = false;

    @Schema(description = "按类型筛选: order/collect/comment，不传为全部")
    private String type;

    @Min(value = 1, message = "页码最小为1")
    @Schema(description = "当前页码,默认为1")
    private Integer pageNum = 1;

    @Min(value = 1, message = "每页条数最小为1")
    @Max(value = 50, message = "每页条数最大为50")
    @Schema(description = "每页显示条数,默认为10")
    private Integer pageSize = 10;
}
