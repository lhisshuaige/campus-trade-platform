package com.campus.trade.service.imp;

import cn.hutool.json.JSONUtil;
import com.campus.trade.bean.utils.CacheUtils;
import com.campus.trade.bean.vo.GoodsVo;
import com.campus.trade.bean.vo.StatisticsOverviewVo;
import com.campus.trade.mapper.StatisticsMapper;
import com.campus.trade.service.GoodsService;
import com.campus.trade.service.StatisticsService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StatisticsServiceImp implements StatisticsService {

    private static final Duration STATISTICS_TTL = Duration.ofMinutes(5);

    @Resource
    private StatisticsMapper statisticsMapper;

    @Resource
    private CacheUtils cacheUtils;

    //排行榜只有一份实现：GoodsService.getCollectRank（读 collect_count + 只统计在售 + 唯一一份缓存 collect:rank）。
    //不再单独写一条 COUNT(collect.id) 的实时聚合 —— 两处各算一次必然漂移，
    //collect 表由校准任务回填进 collect_count 之后，读冗余列既一致又是索引友好的排序
    @Resource
    private GoodsService goodsService;

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
        //不再套一层的 statistics:hotGoods:{limit} 缓存：
        //榜单的唯一缓存已经下沉到 GoodsServiceImp 的 collect:rank（一份 Top50，按 limit 内存截断）。
        //两处各缓存一份会导致失效时机不一致 —— 收藏变了只删得掉内层，外层依旧脏 5 分钟，
        //而把数据扇平成展示行只是一次内存遍历，不值得另开一份缓存
        return goodsService.getCollectRank(limit).stream()
                .map(StatisticsServiceImp::toHotGoodsRow)
                .collect(Collectors.toList());
    }

    //保持原 SQL 别名字段，前端看板无需改动；image 对应 goods.image（实体里叫 imgUrl）
    private static Map<String, Object> toHotGoodsRow(GoodsVo vo) {
        Map<String, Object> row = new LinkedHashMap<>(5);
        row.put("id", vo.getId());
        row.put("title", vo.getTitle());
        row.put("price", vo.getPrice());
        row.put("image", vo.getImgUrl());
        row.put("collectCount", vo.getCollectCount());
        return row;
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
