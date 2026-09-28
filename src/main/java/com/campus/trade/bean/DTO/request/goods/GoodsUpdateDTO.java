package com.campus.trade.bean.DTO.request.goods;

import com.campus.trade.bean.entry.Goods;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "修改商品请求参数")
@Data
public class GoodsUpdateDTO {

    @NotNull(message = "商品id不能为空")
    @Schema(description = "商品id")
    private Long id;

    // 原来这里的 message 与 @Schema 都写着“商品名称”，却挂在 categoryId 上（P2-4）：
    // 前端按提示去查“名称”字段，永远查不到真正为空的是分类
    @NotNull(message = "商品分类不能为空")
    @Schema(description = "商品分类id")
    private Long categoryId;

    @NotBlank(message = "商品标题不能为空")
    @Size(max = Goods.TITLE_MAX_LENGTH, message = "商品标题不能超过" + Goods.TITLE_MAX_LENGTH + "字")
    @Schema(description = "商品标题")
    private String title;

    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0", message = "商品价格不能为负")
    @Digits(integer = Goods.PRICE_INTEGER_DIGITS, fraction = Goods.PRICE_FRACTION_DIGITS,
            message = "价格最多" + Goods.PRICE_INTEGER_DIGITS + "位整数、" + Goods.PRICE_FRACTION_DIGITS + "位小数")
    @Schema(description = "商品价格(标价),>=0")
    private BigDecimal price;

    //下面四个选填字段沿用 updateById 的默认策略（null 字段不参与更新）：不传 = 保持原值。
    //字符串类想改回“没填”就显式传空串；而 BigDecimal 没有“空串”可传，
    //要取消议价就把 expectPrice 传成与标价相同的值（语义上等价于“底线就是标价”）
    @DecimalMin(value = "0", message = "期望成交价不能为负")
    @Digits(integer = Goods.PRICE_INTEGER_DIGITS, fraction = Goods.PRICE_FRACTION_DIGITS,
            message = "期望成交价最多" + Goods.PRICE_INTEGER_DIGITS + "位整数、" + Goods.PRICE_FRACTION_DIGITS + "位小数")
    @Schema(description = "期望成交价(可议价底线),不得高于标价;不传表示不修改")
    private BigDecimal expectPrice;

    @Size(max = Goods.CONDITION_MAX_LENGTH, message = "成色取值不合法")
    @Schema(description = "成色:全新/几乎全新/轻微使用痕迹/明显使用痕迹/有瑕疵可正常使用")
    private String conditionLevel;

    @Size(max = Goods.TRADE_LOCATION_MAX_LENGTH, message = "交易地点不能超过" + Goods.TRADE_LOCATION_MAX_LENGTH + "字")
    @Schema(description = "交易地点")
    private String tradeLocation;

    @Size(max = Goods.CONTACT_MAX_LENGTH, message = "联系方式不能超过" + Goods.CONTACT_MAX_LENGTH + "字")
    @Schema(description = "对外公开的联系方式")
    private String contact;

    @Size(max = Goods.DESC_MAX_LENGTH, message = "商品描述最多" + Goods.DESC_MAX_LENGTH + "字")
    @Schema(description = "商品描述")
    private String description;

    @Size(max = Goods.IMG_URL_MAX_LENGTH, message = "图片地址过长")
    @Schema(description = "商品图片地址")
    private String imgUrl;
}
