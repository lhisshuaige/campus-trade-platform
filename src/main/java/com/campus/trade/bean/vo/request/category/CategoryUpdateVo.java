package com.campus.trade.bean.vo.request.category;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "修改分类请求参数")
@Data
public class CategoryUpdateVo {

    @NotNull(message = "分类id不能为空")
    @Schema(description = "分类id")
    private Long id;

    @NotBlank(message = "分类名称不能为空")
    @Schema(description = "分类名称")
    private String name;

    @Schema(description = "排序值")
    private Integer sort;
}
