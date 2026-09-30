package com.campus.trade.service.imp;

import cn.hutool.json.JSONUtil;
import com.campus.trade.bean.entry.Goods;
import com.campus.trade.bean.utils.CacheUtils;
import com.campus.trade.bean.utils.RedisContent;
import com.campus.trade.bean.vo.CategoryGoodsCountVo;
import com.campus.trade.bean.vo.GoodsStatusCountVo;
import com.campus.trade.bean.vo.GoodsVo;
import com.campus.trade.bean.vo.HotGoodsVo;
import com.campus.trade.bean.vo.PriceRangeCountVo;
import com.campus.trade.bean.vo.StatisticsOverviewVo;
import com.campus.trade.bean.vo.UserGoodsRankVo;
import com.campus.trade.mapper.StatisticsMapper;
import com.campus.trade.service.GoodsService;
import com.campus.trade.service.StatisticsService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * 数据统计。整类只读，所以 {@code readOnly = true} 写在类上：
 * 它的价值不只是"HikariCP 可以省掉一次事务收尾"，而是把「统计接口不许写库」这件事
 * 从一句约定变成运行时约束 —— 以后谁在统计链路里加了写操作，MySQL 会直接把那条语句拒了
 * （ERROR 1792: Cannot execute statement in a READ ONLY transaction），而不是悄悄写进去。
 * <p>
 * 一个边界：这个只读事务只覆盖**同步回源**那一条路。{@code CacheUtils} 的逻辑过期异步重建
 * 跑在重建线程池上，那里没有本事务的上下文，用的是自己那条 auto-commit 连接 ——
 * 所以"readOnly 能拦住写"不能当成全链路保证，它拦的是这个类自己的代码路径
 */
@Transactional(readOnly = true)
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
        return cacheUtils.getOrLoad(RedisContent.statisticsOverviewKey(), STATISTICS_TTL,
                StatisticsOverviewVo.class, statisticsMapper::getOverview);
    }

    @Override
    public List<CategoryGoodsCountVo> getCategoryGoodsCount() {
        return cacheUtils.getOrLoad(RedisContent.statisticsCategoryGoodsKey(), STATISTICS_TTL,
                json -> JSONUtil.parseArray(json).toList(CategoryGoodsCountVo.class),
                statisticsMapper::getCategoryGoodsCount);
    }

    @Override
    public List<GoodsStatusCountVo> getGoodsStatusCount() {
        return cacheUtils.getOrLoad(RedisContent.statisticsGoodsStatusKey(), STATISTICS_TTL,
                json -> JSONUtil.parseArray(json).toList(GoodsStatusCountVo.class),
                this::loadGoodsStatusCount);
    }

    @Override
    public List<HotGoodsVo> getHotGoods(int limit) {
        //不再套一层的 statistics:hotGoods:{limit} 缓存：
        //榜单的唯一缓存已经下沉到 GoodsServiceImp 的 collect:rank（一份 Top50，按 limit 内存截断）。
        //两处各缓存一份会导致失效时机不一致 —— 收藏变了只删得掉内层，外层依旧脏 5 分钟，
        //而把数据扇平成展示行只是一次内存遍历，不值得另开一份缓存
        return goodsService.getCollectRank(limit).stream()
                .map(StatisticsServiceImp::toHotGoodsRow)
                .toList();
    }

    @Override
    public List<PriceRangeCountVo> getPriceRange() {
        return cacheUtils.getOrLoad(RedisContent.statisticsPriceRangeKey(), STATISTICS_TTL,
                json -> JSONUtil.parseArray(json).toList(PriceRangeCountVo.class),
                statisticsMapper::getPriceRange);
    }

    @Override
    public List<UserGoodsRankVo> getUserGoodsRank(int limit) {
        if (limit <= 0 || limit > 50) {
            limit = 10;
        }
        int finalLimit = limit;
        //limit 进了 key：这一份不能像 collect:rank 那样只存一个 key，因为它的 LIMIT 是发给 DB 的，
        //不同 limit 拿到的本来就是不同结果集（区别见 RedisContent.statisticsUserGoodsRankKey 的注释）
        return cacheUtils.getOrLoad(RedisContent.statisticsUserGoodsRankKey(finalLimit), STATISTICS_TTL,
                json -> JSONUtil.parseArray(json).toList(UserGoodsRankVo.class),
                () -> statisticsMapper.getUserGoodsRank(finalLimit));
    }

    //回源 + 翻译：把状态码翻成中文标签这件事放在**读进缓存之前**，
    //于是缓存里存的就是带标签的成品，出参不再依赖调用时再算一次
    //（标签与值域同源：Goods.STATUS_LABELS，SQL 里不写第二份 CASE WHEN）
    private List<GoodsStatusCountVo> loadGoodsStatusCount() {
        List<GoodsStatusCountVo> rows = statisticsMapper.getGoodsStatusCount();
        rows.forEach(row -> row.setStatusLabel(Goods.statusLabel(row.getStatus())));
        return rows;
    }

    //保持原出参字段名（image 对应 goods.image，实体里叫 imgUrl），前端看板无需改动
    private static HotGoodsVo toHotGoodsRow(GoodsVo vo) {
        HotGoodsVo row = new HotGoodsVo();
        row.setId(vo.getId());
        row.setTitle(vo.getTitle());
        row.setPrice(vo.getPrice());
        row.setImage(vo.getImgUrl());
        row.setCollectCount(vo.getCollectCount());
        return row;
    }
}
