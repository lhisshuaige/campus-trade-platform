package com.campus.trade.service;

import com.campus.trade.bean.vo.CategoryGoodsCountVo;
import com.campus.trade.bean.vo.GoodsStatusCountVo;
import com.campus.trade.bean.vo.HotGoodsVo;
import com.campus.trade.bean.vo.PriceRangeCountVo;
import com.campus.trade.bean.vo.StatisticsOverviewVo;
import com.campus.trade.bean.vo.UserGoodsRankVo;

import java.util.List;

/**
 * 数据统计。出参全部是 VO 而不是 {@code List<Map<String, Object>>}（P3-9）：
 * Map 版本把「这一行有哪些字段、类型是什么」这件事只留在 SQL 里，
 * 编译期查不出拼错的 key，Swagger 上也画不出出参结构，前端只能靠打印响应猜
 */
public interface StatisticsService {

    // 平台数据概览
    StatisticsOverviewVo getOverview();

    // 各分类商品数量统计
    List<CategoryGoodsCountVo> getCategoryGoodsCount();

    // 商品状态分布（带中文标签，见 GoodsStatusCountVo）
    List<GoodsStatusCountVo> getGoodsStatusCount();

    // 热门商品排行
    List<HotGoodsVo> getHotGoods(int limit);

    // 商品价格区间分布
    List<PriceRangeCountVo> getPriceRange();

    // 卖家发布商品排行
    List<UserGoodsRankVo> getUserGoodsRank(int limit);
}
