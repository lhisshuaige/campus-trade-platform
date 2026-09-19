package com.campus.trade.service;

import com.campus.trade.bean.vo.StatisticsOverviewVo;

import java.util.List;
import java.util.Map;

public interface StatisticsService {

    // 平台数据概览
    StatisticsOverviewVo getOverview();

    // 各分类商品数量统计
    List<Map<String, Object>> getCategoryGoodsCount();

    // 商品状态分布
    List<Map<String, Object>> getGoodsStatusCount();

    // 热门商品排行
    List<Map<String, Object>> getHotGoods(int limit);

    // 商品价格区间分布
    List<Map<String, Object>> getPriceRange();

    // 卖家发布商品排行
    List<Map<String, Object>> getUserGoodsRank(int limit);
}
