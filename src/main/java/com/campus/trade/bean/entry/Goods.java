package com.campus.trade.bean.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@TableName("goods")
@Data
public class Goods {

    // 商品状态：取值域必须和 init.sql 的 chk_goods_status CHECK (status IN (0,1,2)) 一致
    public static final int STATUS_OFF = 0;      // 已下架
    public static final int STATUS_ON_SALE = 1;  // 在售
    public static final int STATUS_SOLD = 2;     // 已售出（被订单占用）

    /**
     * 状态的中文标签：统计接口要给看板直接画图，不能再让前端写一份 switch。
     * 写在这里是因为取值域本来就定义在本类（上面三个常量），标签与值域分开两处必然漂移；
     * init.sql 里那条 CHECK 是第三处，但它只能表达「只能是 0/1/2」，说不出人话
     */
    public static final Map<Integer, String> STATUS_LABELS = Map.of(
            STATUS_OFF, "已下架",
            STATUS_ON_SALE, "在售",
            STATUS_SOLD, "已售出");

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
    /**
     * 逻辑删除位（@TableLogic）。MP 会给<b>它自己生成的</b> SQL 自动追加 `deleted = 0`：
     * getById / list(wrapper) / page(wrapper) / updateById / removeById 全部覆盖，
     * 而 removeById 会从 DELETE 变成 UPDATE —— 于是有两个后续影响必须知道：
     * ① 手写 @Select/@Update（CollectMapper、StatisticsMapper、GoodsMapper 那几条）**不受保护**，
     *    要自己带 `deleted = 0`，漏写不会报错，只会把已删的东西重新显示给用户看；
     * ② `fk_order_goods` 那条 RESTRICT 再也触发不到了（不发生物理删除，FK 根本不参与），
     *    所以 deleteGoods 里"防并发下单"的那道防线换成条件删除 `status <> 已售出`。
     */
    @TableLogic
    private Integer deleted;
    /**
     * 乐观锁版本号（@Version）。刻意**只服务一条路径**：`updateGoods` 是「读整行 → 改几个字段 → 写回整行」，
     * 两个端同时改同一件商品，后提交的那一份会把前一份的字段值一起覆盖（不是只覆盖自己改的那一列）。
     * 状态迁移（上架/下架/售出）仍是单列 CAS，比版本号更直接也不需要多一次比对；
     * 所以这一列不是「给每张表都补一个 @Version」的开头，而是那条整行覆盖的唯一护栏。
     * 版本号的值**绝不从前端传**（DTO 里没有 version），一律 Service 从库里读出来再写进实体，
     * 否则"传什么版本号"等于把"我要覆盖哪个版本"交到客户端手里
     */
    @Version
    private Integer version;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /**
     * 状态转中文标签。`Map.of` 造的不可变 Map 对 null key 是**抛 NPE**而不是返 null，
     * 所以这里先夹一层；而“未知值”给的是标签而不是异常 —— 统计是读侧，
     * 为一行历史脏数据把整个看板打死，比看板少翻译一个词严重得多
     */
    public static String statusLabel(Integer status) {
        return status == null ? "未知" : STATUS_LABELS.getOrDefault(status, "未知");
    }
}
