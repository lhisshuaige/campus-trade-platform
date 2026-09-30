package com.campus.trade.bean.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 统计·卖家发布商品排行（看板用，仅 admin 可见）
 */
@Schema(description = "卖家发布量排行行")
@Data
public class UserGoodsRankVo {

    // 这是 admin 接口，所以给得出 userId（后台要点进某个卖家的主页/治理列表时用它当参数）；
    // 换成 C 端接口时这一列就必须删掉 —— 同一个字段该不该出参，取决于谁能看到它
    @Schema(description = "卖家用户ID")
    private Long userId;

    @Schema(description = "卖家昵称")
    private String nickname;

    @Schema(description = "发布商品数（已删除的商品不计入）")
    private Long goodsCount;
}
