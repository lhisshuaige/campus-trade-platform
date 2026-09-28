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

@Schema(description = "添加商品请求参数")
@Data
public class GoodsAddDTO {

    @NotNull(message = "商品分类id不能为空,请选择商品id")
    @Schema(description = "商品分类id")
    private Long categoryId;

    //title 必须在这里拦长度：GoodsServiceImp.checkGoodsContent 的注释早就写了“长度由 DTO 的 @Size 拦”，
    //但注解一直没落地 —— 超长要么被 MySQL 严格模式报 1406 变 400 里最看不懂的那句，要么被静默截断
    @NotBlank(message = "商品标题不能为空")
    @Size(max = Goods.TITLE_MAX_LENGTH, message = "商品标题不能超过" + Goods.TITLE_MAX_LENGTH + "字")
    @Schema(description = "商品标题")
    private String title;

    @NotNull(message = "商品价格不能为空")
    //下限取 0 而不是 0.01：chk_goods_price 就是 price >= 0，Java 侧比 DB 更严会让那条 CHECK 形同虚设，
    //而“免费送”在校园场景里是真实业务（本项目下单不涉及资金，0 元没有套利面）
    @DecimalMin(value = "0", message = "商品价格不能为负")
    @Digits(integer = Goods.PRICE_INTEGER_DIGITS, fraction = Goods.PRICE_FRACTION_DIGITS,
            message = "价格最多" + Goods.PRICE_INTEGER_DIGITS + "位整数、" + Goods.PRICE_FRACTION_DIGITS + "位小数")
    @Schema(description = "商品价格(标价),>=0")
    private BigDecimal price;

    //可议价底线：不传就走"不议价"，所以不加 @NotNull；高于标价在 Service 里拦（给 400 而不是 DB CHECK 的 500）
    //为什么不交给 Bean Validation：这是跨字段规则，做它要引 @AssertTrue 或类级约束，收益不值当
    @DecimalMin(value = "0", message = "期望成交价不能为负")
    @Digits(integer = Goods.PRICE_INTEGER_DIGITS, fraction = Goods.PRICE_FRACTION_DIGITS,
            message = "期望成交价最多" + Goods.PRICE_INTEGER_DIGITS + "位整数、" + Goods.PRICE_FRACTION_DIGITS + "位小数")
    @Schema(description = "期望成交价(可议价底线),可不传,不得高于标价")
    private BigDecimal expectPrice;

    //成色只给取值域不给自由文本：写"九成新"和"9成新"的人其实想表达同一件事
    //（具体白名单在 Goods.CONDITIONS，和 init.sql 的 chk_goods_condition 同源）
    @Size(max = Goods.CONDITION_MAX_LENGTH, message = "成色取值不合法")
    @Schema(description = "成色:全新/几乎全新/轻微使用痕迹/明显使用痕迹/有瑕疵可正常使用")
    private String conditionLevel;

    @Size(max = Goods.TRADE_LOCATION_MAX_LENGTH, message = "交易地点不能超过" + Goods.TRADE_LOCATION_MAX_LENGTH + "字")
    @Schema(description = "交易地点,如:东三门/3号宿舍楼下")
    private String tradeLocation;

    @Size(max = Goods.CONTACT_MAX_LENGTH, message = "联系方式不能超过" + Goods.CONTACT_MAX_LENGTH + "字")
    @Schema(description = "对外公开的联系方式(微信/QQ/手机号),不填则详情页不展示联系入口")
    private String contact;

    @Size(max = Goods.DESC_MAX_LENGTH, message = "商品描述最多" + Goods.DESC_MAX_LENGTH + "字")
    @Schema(description = "商品描述")
    private String description;

    @Size(max = Goods.IMG_URL_MAX_LENGTH, message = "图片地址过长")
    @Schema(description = "商品图片地址")
    private String imgUrl;
}
