package com.campus.trade.mapper;

import com.campus.trade.bean.vo.result.StatisticsOverviewVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 数据统计 Mapper，通过聚合查询实现数据加工处理
 */
@Mapper
public interface StatisticsMapper {

    // 平台整体数据概览
    @Select("SELECT " +
            "(SELECT COUNT(*) FROM `user`) AS user_count, " +
            "(SELECT COUNT(*) FROM goods) AS goods_count, " +
            "(SELECT COUNT(*) FROM goods WHERE status = 1) AS on_sale_count, " +
            "(SELECT COUNT(*) FROM `order`) AS order_count, " +
            "(SELECT COUNT(*) FROM `order` WHERE status = 2) AS completed_order_count, " +
            "(SELECT IFNULL(SUM(price), 0) FROM `order` WHERE status = 2) AS total_amount")
    StatisticsOverviewVo getOverview();

    // 各分类商品数量统计
    @Select("SELECT c.name AS name, COUNT(g.id) AS count " +
            "FROM category c LEFT JOIN goods g ON c.id = g.category_id " +
            "GROUP BY c.id, c.name ORDER BY c.sort")
    List<Map<String, Object>> getCategoryGoodsCount();

    // 商品状态分布统计（0-下架 1-在售 2-已售出）
    @Select("SELECT status AS status, COUNT(*) AS count FROM goods GROUP BY status")
    List<Map<String, Object>> getGoodsStatusCount();

    // 热门商品排行（按收藏数降序）
    @Select("SELECT g.id AS id, g.title AS title, g.price AS price, g.image AS image, " +
            "COUNT(c.id) AS collectCount " +
            "FROM goods g LEFT JOIN collect c ON g.id = c.goods_id " +
            "GROUP BY g.id, g.title, g.price, g.image " +
            "ORDER BY collectCount DESC, g.create_time DESC LIMIT #{limit}")
    List<Map<String, Object>> getHotGoods(@Param("limit") int limit);

    // 商品价格区间分布
    @Select("SELECT CASE " +
            "WHEN price < 50 THEN '50元以下' " +
            "WHEN price < 100 THEN '50-100元' " +
            "WHEN price < 200 THEN '100-200元' " +
            "WHEN price < 500 THEN '200-500元' " +
            "ELSE '500元以上' END AS rangeName, " +
            "COUNT(*) AS count FROM goods GROUP BY rangeName ORDER BY MIN(price)")
    List<Map<String, Object>> getPriceRange();

    // 卖家发布商品数量排行
    @Select("SELECT u.id AS userId, u.nickname AS nickname, COUNT(g.id) AS goodsCount " +
            "FROM `user` u LEFT JOIN goods g ON u.id = g.user_id " +
            "GROUP BY u.id, u.nickname ORDER BY goodsCount DESC LIMIT #{limit}")
    List<Map<String, Object>> getUserGoodsRank(@Param("limit") int limit);
}
