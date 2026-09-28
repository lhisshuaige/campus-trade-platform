package com.campus.trade.service.imp;

import com.campus.trade.bean.utils.CacheUtils;
import com.campus.trade.bean.utils.RedisContent;
import com.campus.trade.bean.utils.ViewCountUtils;
import com.campus.trade.mapper.GoodsMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 浏览量增量落库：把 Redis 里累计的 PV 搬进 goods.view_count。
 *
 * 与 CollectCountCalibrationJob 是同一套路的两个方向：
 * 收藏数以 collect 表为真相源，对账是「用真相覆盖冗余列」；
 * 浏览量没有别的真相源，Redis 就是唯一缓冲，落库是「把增量累加进存量」。
 *
 * 顺序刻意是「先加 DB，成功后再扣 Redis」：中间进程挂掉最多让同一批 PV 重复计一次，
 * 反过来（先扣再加）就是净丢失。浏览量可以偏高不可偏低——对卖家而言"更多人看过"至少不是坏消息。
 */
@Slf4j
@Component
public class ViewCountFlushJob {

    @Resource
    private ViewCountUtils viewCountUtils;
    @Resource
    private GoodsMapper goodsMapper;
    @Resource
    private CacheUtils cacheUtils;

    //商品已删除时 Redis 里那条增量会一直留着（DB 改不到行）。字段数量以"曾被浏览过的商品数"为上界，
    //先不为这个死数据加清理逻辑；真出现了再用 snapshotDeltas 里的 field 直接 HINCRBY 负数扣平
    @Value("${app.view-count.max-per-round:2000}")
    private int maxPerRound;

    //默认 5 分钟一轮。这个间隔同时决定"列表页浏览量的最大滞后量"，
    //调太长列表数字明显偏低，调太短就是拿 DB 写换显示精度
    @Scheduled(fixedDelayString = "${app.view-count.flush-interval-ms:300000}")
    public void flushViewCount() {
        Map<Long, Long> deltas = viewCountUtils.snapshotDeltas();
        if (deltas.isEmpty()) {
            return;
        }
        long moved = 0L;
        int settled = 0, rounds = 0;
        for (Map.Entry<Long, Long> entry : deltas.entrySet()) {
            //单轮限量：极端情况（一次流量高峰）下不把几万条 UPDATE 压成一次长事务
            if (++rounds > maxPerRound) {
                log.warn("浏览量落库达到单轮上限 {}，剩余增量下一轮继续", maxPerRound);
                break;
            }
            Long goodsId = entry.getKey();
            Long delta = entry.getValue();
            try {
                if (goodsMapper.incrViewCount(goodsId, delta) > 0) {
                    viewCountUtils.settleDelta(goodsId, delta);
                    settled++;
                    moved += delta;
                    //view_count 就在被缓存的 Goods 里，不落库后清掉缓存，详情页会拿着旧存量继续加增量
                    //（job 无事务，evictAfterCommit 内部会走"立即删 + 延迟双删"）
                    cacheUtils.evictAfterCommit(RedisContent.goodsDetailKey(goodsId));
                }
            } catch (Exception e) {
                //单条失败必须吞掉：一个坏商品不能中断整批落库；不 settle 就等于下一轮自动重试
                log.warn("浏览量落库失败 goodsId={} delta={}", goodsId, delta, e);
            }
        }
        log.info("浏览量落库完成 {}/{} 个商品，累计写入 {} PV", settled, deltas.size(), moved);
    }
}
