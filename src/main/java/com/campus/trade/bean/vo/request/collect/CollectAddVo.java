package com.campus.trade.bean.vo.request.collect;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "添加收藏请求参数,前端发送给后端的")
public class CollectAddVo {
    @NotNull(message = "商品id不能为空")
    @Schema(description = "商品id")
    private Long goodsId;
}
