package com.campus.trade.bean.DTO.request.category;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;


@Schema(description = "添加分类请求参数")
@Data
public class CategoryAddDTO {


    @NotBlank(message = "分类名称不能为空")
    @Schema(description = "分类名称")
    private String name;


    @Schema(description = "排序值")
    private Integer sort;

}
