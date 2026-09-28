package com.campus.trade.service.imp;

import com.campus.trade.bean.utils.CacheUtils;
import com.campus.trade.bean.utils.RedisContent;
import com.campus.trade.mapper.GoodsMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * collect_count 冗余列对账校准。
 * 口径定义：collect 表是唯一真相源，goods.collect_count 只是「读优化的物化计数」，
 * 允许毫秒级偏差（增量 +1/-1），不允许长期漂移（护栏 collect_count > 0 会把偏差永久固化）。
 * 与 OrderTimeoutFallbackJob 同一套路：及时通道保体验，对账通道保正确性。
 */
@Slf4j
@Component
public class CollectCountCalibrationJob {

    @Resource
    private GoodsMapper goodsMapper;
    @Resource
    private CacheUtils cacheUtils;

    @Value("${app.collect-calibration.batch-size:500}")
    private int batchSize;

    //默认每天 03:30，避开业务高峰；cron 可配，本地想验就改成 fixedDelayString
    @Scheduled(cron = "${app.collect-calibration.cron:0 30 3 * * ?}")
    public void calibrateCollectCount() {
        List<Map<String, Object>> drift = goodsMapper.selectCollectCountDrift(batchSize);
        if (drift.isEmpty()) {
            return;
        }
        //发现漂移本身就是信号：要么有绕过 Service 的写入，要么增量维护漏了口子
        log.warn("收藏数对账发现 {} 条漂移记录（单轮上限 {}）", drift.size(), batchSize);
        int fixed = 0;
        for (Map<String, Object> row : drift) {
            try {
                Long goodsId = ((Number) row.get("goodsId")).longValue();
                int realCount = ((Number) row.get("realCount")).intValue();
                int wrongCount = ((Number) row.get("wrongCount")).intValue();
                if (goodsMapper.fixCollectCount(goodsId, realCount, wrongCount) > 0) {
                    fixed++;
                    //不删的话详情页最长还脏 30 分钟；job 无事务，内部会走"立即删 + 延迟双删"
                    cacheUtils.evictAfterCommit(RedisContent.goodsDetailKey(goodsId));
                }
            } catch (Exception e) {
                //单条失败必须吞掉：一条毒数据不能中断整批对账
                log.warn("收藏数校准失败 row={}", row, e);
            }
        }
        //收藏数就是榜单的排序键，修过数据就必须重算。
        //榜单只有一个 Top50 key，所以放在循环外删一次，不逐条删
        if (fixed > 0) {
            cacheUtils.evictAfterCommit(RedisContent.Collect_Rank_KEY);
        }
        log.info("收藏数校准完成，修正 {}/{} 条", fixed, drift.size());
    }
}