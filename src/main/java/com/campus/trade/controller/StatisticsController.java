package com.campus.trade.controller;

import com.campus.trade.bean.entry.Role;
import com.campus.trade.bean.utils.RequireRole;
import com.campus.trade.bean.vo.result.MyResult;
import com.campus.trade.bean.vo.result.StatisticsOverviewVo;
import com.campus.trade.service.StatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据统计接口：对平台数据进行聚合分析、加工处理
 */
@RestController
@RequestMapping("/statistics")
@Tag(name = "数据统计接口", description = "平台数据统计分析")
@RequireRole(Role.ADMIN)
public class StatisticsController {

    @Resource
    private StatisticsService statisticsService;

    @GetMapping("/overview")
    @Operation(summary = "平台数据概览", description = "统计用户数、商品数、订单数、成交总额等")
    public MyResult<StatisticsOverviewVo> overview() {
        return MyResult.success(statisticsService.getOverview());
    }

    @GetMapping("/categoryGoods")
    @Operation(summary = "各分类商品数量统计")
    public MyResult<List<Map<String, Object>>> categoryGoods() {
        return MyResult.success(statisticsService.getCategoryGoodsCount());
    }

    @GetMapping("/goodsStatus")
    @Operation(summary = "商品状态分布统计")
    public MyResult<List<Map<String, Object>>> goodsStatus() {
        return MyResult.success(statisticsService.getGoodsStatusCount());
    }

    @GetMapping("/hotGoods")
    @Operation(summary = "热门商品排行", description = "按收藏数降序排列")
    public MyResult<List<Map<String, Object>>> hotGoods(@RequestParam(defaultValue = "10") Integer limit) {
        return MyResult.success(statisticsService.getHotGoods(limit));
    }

    @GetMapping("/priceRange")
    @Operation(summary = "商品价格区间分布")
    public MyResult<List<Map<String, Object>>> priceRange() {
        return MyResult.success(statisticsService.getPriceRange());
    }

    @GetMapping("/userGoodsRank")
    @Operation(summary = "卖家发布商品排行")
    public MyResult<List<Map<String, Object>>> userGoodsRank(@RequestParam(defaultValue = "10") Integer limit) {
        return MyResult.success(statisticsService.getUserGoodsRank(limit));
    }
}
