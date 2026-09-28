package com.campus.trade.bean.utils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览量计数：Redis 缓冲增量，DB 存存量，定时任务把增量搬进 goods.view_count。
 *
 * 为什么不在详情接口里直接 {@code UPDATE goods SET view_count = view_count + 1}：
 * 详情页是全站最热的读路径，而且 view_count 就躺在被缓存的 Goods 里 ——
 * 每读一次就写一次行锁，还得跟着 evict 详情缓存，等于自己把缓存打穿。
 * 现在一条读路径的成本是：一次缓存命中 + 一次 HINCRBY，DB 零写入。
 *
 * 代价（必须说清）：浏览量是最终一致的展示值。Redis 崩了会丢掉「最多一个落库周期」的增量，
 * 落库任务用「先加 DB、成功后再扣 Redis」的顺序，宁可重复计几条也不丢 —— 它不是钱。
 *
 * 口径：这是 PV（页面浏览次数），不是 UV（访客数）。同一人刷新会重复计数，
 * 要做 UV 就再加一层 goods:view:uv:{goodsId}:{userId} 的 SETNX 门（多一次命令），当前不值当。
 */
@Slf4j
@Component
public class ViewCountUtils {

    @Resource
    private SafeRedis safeRedis;

    /**
     * 记一次浏览，返回该商品当前尚未落库的增量（HINCRBY 的返回值就是新的累计值，
     * 所以详情页只需一条命令就能“计数 + 拿到实时增量”）。Redis 不可用时返回 0 = 不展示增量。
     */
    public long recordView(Long goodsId) {
        Long delta = safeRedis.hIncrByOrNull(RedisContent.Goods_View_Delta_KEY, field(goodsId), 1L);
        return delta == null ? 0L : delta;
    }

    /** 快照所有「有增量」的商品：一次 HGETALL 就够，不需要 SCAN（这是选 hash 而非独立 key 的全部理由） */
    public Map<Long, Long> snapshotDeltas() {
        Map<Long, Long> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : safeRedis.hEntriesOrEmpty(RedisContent.Goods_View_Delta_KEY).entrySet()) {
            Long goodsId = parseField(e.getKey());
            if (goodsId == null) {
                continue;
            }
            long delta = parseDelta(String.valueOf(e.getKey()), e.getValue() == null ? null : e.getValue().toString());
            if (delta > 0) {
                result.put(goodsId, delta);
            }
        }
        return result;
    }

    /** 增量已进 DB，把 Redis 里这部分扣掉（扣成 0 也比留着强，否则下轮会重复落库） */
    public void settleDelta(Long goodsId, long delta) {
        safeRedis.hIncrBySwallow(RedisContent.Goods_View_Delta_KEY, field(goodsId), -delta);
    }

    private String field(Long goodsId) {
        return String.valueOf(goodsId);
    }

    //脏数据（人工改过 hash）一律当 0，绝不让一个坏字段把整批落库打断
    private long parseDelta(String field, String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("浏览量增量脏数据，按 0 处理 field={} value={}", field, value);
            return 0L;
        }
    }

    private Long parseField(Object key) {
        try {
            return Long.parseLong(String.valueOf(key).trim());
        } catch (NumberFormatException e) {
            log.warn("浏览量增量 field 不是商品id，跳过 field={}", key);
            return null;
        }
    }
}
