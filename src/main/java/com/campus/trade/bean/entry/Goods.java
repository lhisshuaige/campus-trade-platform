package com.campus.trade.bean.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@TableName("goods")
@Data
public class Goods {

    // 商品状态：取值域必须和 init.sql 的 chk_goods_status CHECK (status IN (0,1,2)) 一致
    public static final int STATUS_OFF = 0;      // 已下架
    public static final int STATUS_ON_SALE = 1;  // 在售
    public static final int STATUS_SOLD = 2;     // 已售出（被订单占用）

    // 成色取值域：与 init.sql 的 chk_goods_condition 同一份口径。
    // 不做成自由文本是因为"九成新/9成新/95新"会把同一档拆成三堆，既统计不了也没法筛
    public static final List<String> CONDITIONS = List.of(
            "全新", "几乎全新", "轻微使用痕迹", "明显使用痕迹", "有瑕疵可正常使用");

    // ==== 入参长度与金额口径：DTO 注解直接引用，与 init.sql 的列宽一一对应，不各处抄数字 ====
    public static final int TITLE_MAX_LENGTH = 200;           // 对应 title VARCHAR(200)
    public static final int TRADE_LOCATION_MAX_LENGTH = 100;  // 对应 trade_location VARCHAR(100)
    public static final int CONTACT_MAX_LENGTH = 100;         // 对应 contact VARCHAR(100)
    public static final int CONDITION_MAX_LENGTH = 20;        // 对应 condition_level VARCHAR(20)
    public static final int IMG_URL_MAX_LENGTH = 500;         // 对应 image VARCHAR(500)

    /**
     * 商品描述上限：DB 侧是 TEXT（64KB）但**刻意不建长度 CHECK** —— 与 comment.content 同一个决定：
     * 长度是业务规则而不是数据完整性，写进 DB 只会多出一处需要同步的口径。
     * 这里真正在管的是「详情页要替用户渲染多少字」。
     */
    public static final int DESC_MAX_LENGTH = 2000;

    /** 金额口径：DECIMAL(10,2) = 整数最多 8 位、小数 2 位。不写 fraction 限制时 1.999 会被 DB 静默四舍五入成 2.00 */
    public static final int PRICE_INTEGER_DIGITS = 8;
    public static final int PRICE_FRACTION_DIGITS = 2;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long categoryId;
    private String title;
    private BigDecimal price;
    //期望成交价 = 可议价的底线，必须 <= price（DB 有 chk_goods_expect_price 兜底）
    private BigDecimal expectPrice;
    //成色，二手交易的核心决策信息，取值域见 CONDITIONS
    private String conditionLevel;
    //交易地点，校园场景多是当面自提，地点决定买家要不要跑一趟
    private String tradeLocation;
    //发布者主动公开的联系方式（微信/QQ/手机号）。属于"他愿意给出去"的信息，
    //但也不给匿名访客：读侧按登录态决定是否返回，见 GoodsServiceImp.getGoodsDetail
    private String contact;
    @TableField("image")
    private String imgUrl;
    private String description;
    private Integer status;
    private Integer collectCount;
    //浏览量存量：实时值 = 本列 + Redis 里尚未落库的增量（PV 不落每次写，见 ViewCountFlushJob）
    private Integer viewCount;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
