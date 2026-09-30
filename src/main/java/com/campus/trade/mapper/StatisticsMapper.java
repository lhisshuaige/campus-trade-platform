package com.campus.trade.mapper;

import com.campus.trade.bean.vo.CategoryGoodsCountVo;
import com.campus.trade.bean.vo.GoodsStatusCountVo;
import com.campus.trade.bean.vo.PriceRangeCountVo;
import com.campus.trade.bean.vo.StatisticsOverviewVo;
import com.campus.trade.bean.vo.UserGoodsRankVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据统计 Mapper，通过聚合查询实现数据加工处理。
 * <p>
 * 这里是本项目最需要小心的一栏 SQL：goods 与 comment 从 P3-5 起是**逻辑删除**，
 * 而 @TableLogic 只给 MyBatis-Plus 自己生成的 SQL 追加 {@code deleted = 0}，
 * 手写的 @Select 不受保护 —— 漏写不会报错，只会把已删除的商品算进看板、把统计数字永久抬高。
 * 所以本文件里每一条碰 goods 的 SQL 都必须显式带上这个条件（新增查询时同理）
 */
@Mapper
public interface StatisticsMapper {

    // 平台整体数据概览。三个 goods 子查询都要 deleted = 0：
    // 商品总数与"在售数"的口径必须一致，否则概览卡片自己跟自己差一个数
    @Select("SELECT " +
            "(SELECT COUNT(*) FROM `user`) AS user_count, " +
            "(SELECT COUNT(*) FROM goods WHERE deleted = 0) AS goods_count, " +
            "(SELECT COUNT(*) FROM goods WHERE status = 1 AND deleted = 0) AS on_sale_count, " +
            "(SELECT COUNT(*) FROM `order`) AS order_count, " +
            "(SELECT COUNT(*) FROM `order` WHERE status = 2) AS completed_order_count, " +
            "(SELECT IFNULL(SUM(price), 0) FROM `order` WHERE status = 2) AS total_amount")
    StatisticsOverviewVo getOverview();

    // 各分类商品数量统计。
    // deleted = 0 必须写在 ON 上而不是 WHERE 里：这是 LEFT JOIN，写进 WHERE 会把
    // "一个商品都没有的分类"整行过滤掉（NULL <> 0 不成立），看板上就会少一根柱子而不是显示 0
    @Select("SELECT c.name AS name, COUNT(g.id) AS count " +
            "FROM category c LEFT JOIN goods g ON c.id = g.category_id AND g.deleted = 0 " +
            "GROUP BY c.id, c.name ORDER BY c.sort")
    List<CategoryGoodsCountVo> getCategoryGoodsCount();

    // 商品状态分布统计（0-下架 1-在售 2-已售出）。
    // 只返原值不返中文标签：标签口径定义在 Goods.STATUS_LABELS，翻译放在 Service，
    // 免得 CASE WHEN 在这份 SQL 里再抄一份"0 是什么 1 是什么"
    @Select("SELECT status AS status, COUNT(*) AS count FROM goods WHERE deleted = 0 GROUP BY status")
    List<GoodsStatusCountVo> getGoodsStatusCount();

    // 商品价格区间分布。GROUP BY 的是 CASE 的别名（MySQL 允许，标准 SQL 不允许，换库时要重写这里）
    @Select("SELECT CASE " +
            "WHEN price < 50 THEN '50元以下' " +
            "WHEN price < 100 THEN '50-100元' " +
            "WHEN price < 200 THEN '100-200元' " +
            "WHEN price < 500 THEN '200-500元' " +
            "ELSE '500元以上' END AS rangeName, " +
            "COUNT(*) AS count FROM goods WHERE deleted = 0 GROUP BY rangeName ORDER BY MIN(price)")
    List<PriceRangeCountVo> getPriceRange();

    // 卖家发布商品数量排行。同样是 LEFT JOIN + 过滤条件写在 ON 上
    //（一个商品都没发过的账号也要出现在榜上给 0，否则后台看不到"注册了但没卖过东西"的人）
    @Select("SELECT u.id AS userId, u.nickname AS nickname, COUNT(g.id) AS goodsCount " +
            "FROM `user` u LEFT JOIN goods g ON u.id = g.user_id AND g.deleted = 0 " +
            "GROUP BY u.id, u.nickname ORDER BY goodsCount DESC LIMIT #{limit}")
    List<UserGoodsRankVo> getUserGoodsRank(@Param("limit") int limit);
}
