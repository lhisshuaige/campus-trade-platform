package com.campus.trade.service.imp;

import cn.hutool.json.JSONUtil;
import com.campus.trade.bean.utils.CacheUtils;
import com.campus.trade.bean.vo.StatisticsOverviewVo;
import com.campus.trade.mapper.StatisticsMapper;
import com.campus.trade.service.StatisticsService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class StatisticsServiceImp implements StatisticsService {

    private static final Duration STATISTICS_TTL = Duration.ofMinutes(5);

    @Resource
    private StatisticsMapper statisticsMapper;

    @Resource
    private CacheUtils cacheUtils;

    @Override
    public StatisticsOverviewVo getOverview() {
        return cacheUtils.getOrLoad("statistics:overview", STATISTICS_TTL,
                StatisticsOverviewVo.class, statisticsMapper::getOverview);
    }

    @Override
    public List<Map<String, Object>> getCategoryGoodsCount() {
        return cacheUtils.getOrLoad("statistics:categoryGoods", STATISTICS_TTL,
                StatisticsServiceImp::parseMapList, statisticsMapper::getCategoryGoodsCount);
    }

    @Override
    public List<Map<String, Object>> getGoodsStatusCount() {
        return cacheUtils.getOrLoad("statistics:goodsStatus", STATISTICS_TTL,
                StatisticsServiceImp::parseMapList, statisticsMapper::getGoodsStatusCount);
    }

    @Override
    public List<Map<String, Object>> getHotGoods(int limit) {
        if (limit <= 0 || limit > 50) {
            limit = 10;
        }
        int finalLimit = limit;
        return cacheUtils.getOrLoad("statistics:hotGoods:" + finalLimit, STATISTICS_TTL,
                StatisticsServiceImp::parseMapList, () -> statisticsMapper.getHotGoods(finalLimit));
    }

    @Override
    public List<Map<String, Object>> getPriceRange() {
        return cacheUtils.getOrLoad("statistics:priceRange", STATISTICS_TTL,
                StatisticsServiceImp::parseMapList, statisticsMapper::getPriceRange);
    }

    @Override
    public List<Map<String, Object>> getUserGoodsRank(int limit) {
        if (limit <= 0 || limit > 50) {
            limit = 10;
        }
        int finalLimit = limit;
        return cacheUtils.getOrLoad("statistics:userGoodsRank:" + finalLimit, STATISTICS_TTL,
                StatisticsServiceImp::parseMapList, () -> statisticsMapper.getUserGoodsRank(finalLimit));
    }

    //把缓存的 JSON 数组反序列化成 List<Map<String, Object>>
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseMapList(String json) {
        return (List<Map<String, Object>>) (List<?>) JSONUtil.parseArray(json).toList(Map.class);
    }
}
