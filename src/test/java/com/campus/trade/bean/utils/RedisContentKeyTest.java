package com.campus.trade.bean.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Redis key 的物理名字断言（P3-11，配合 P3-7 / P3-19）。
 * <p>
 * 这批断言的方向和普通测试相反，值得先说清楚：它**不是为了证明 key 拼装是对的**，
 * 而是为了拦住"有人顺手把 key 名改规范了"。
 * <p>
 * {@code icr:} 这个前缀看起来就是个错误（多一个冒号、缩写看不出含义），
 * 但物理 key 名一改，当天那个 INCR 序列就会从 1 重新开始，而分布式 ID = 秒级时间戳&lt;&lt;32 | 序列，
 * 同一秒内新旧两个 key 各发到序列 1 会算出**同一个 ID** —— 那是个真实的重复风险，
 * 所以 P3-7 的交付是"把字面量搬进 RedisContent，一个字节都不改名字"。
 * 没有这条测试的话，半年后一次"顺手规范化命名"的重构就会把这个决定默默推翻，而且不报错。
 */
class RedisContentKeyTest {

    @Test
    @DisplayName("带 id 的缓存 key：前缀 + id，一次拼装成一个确定字符串（写侧要能算出该删哪个）")
    void keyedPrefixesComposeDeterministically() {
        assertEquals("goods:detail:7", RedisContent.goodsDetailKey(7L));
        // comment:list 带版本段是刻意的：v2 楼内正序→倒序，v3 回楼主主评论一组置顶（+行形状加
        // replyToCommentId）—— 语义翻转旧缓存无从察觉，只能换 key；下次再翻请继续 v4，
        // 而不是悄悄复用旧 key 让 5 分钟逻辑 TTL 去"自愈"
        assertEquals("comment:list:v3:7", RedisContent.commentListKey(7L));
        assertEquals("lock:goods:7", RedisContent.Goods_Lock_KEY + 7L);
        assertEquals("user:tokenVersion:7", RedisContent.User_Token_Version_KEY + 7L);
    }

    @Test
    @DisplayName("统计类 key 收在 statistics: 下面，维度名不许再散落各处")
    void statisticsKeysAreCentralized() {
        assertEquals("statistics:", RedisContent.Statistics_KEY);
        assertEquals("statistics:overview", RedisContent.statisticsOverviewKey());
        assertEquals("statistics:categoryGoods", RedisContent.statisticsCategoryGoodsKey());
        assertEquals("statistics:goodsStatus", RedisContent.statisticsGoodsStatusKey());
        assertEquals("statistics:priceRange", RedisContent.statisticsPriceRangeKey());
    }

    @Test
    @DisplayName("两条相反的判据同时成立：卖家排行带 limit 后缀，收藏榜单不带 —— 这不是不一致，是两种数据形态")
    void limitSuffixIsPresentOnOneRankAndAbsentOnTheOther() {
        // statistics:userGoodsRank:{limit} 的 LIMIT 是发给 DB 的，不同 limit 本来就是不同结果集
        assertEquals("statistics:userGoodsRank:3", RedisContent.statisticsUserGoodsRankKey(3));
        assertEquals("statistics:userGoodsRank:5", RedisContent.statisticsUserGoodsRankKey(5));
        // collect:rank 只缓存 Top50 一份全集、在内存里截断；带上 limit 就没法在写侧枚举要失效哪些 key
        assertEquals("collect:rank", RedisContent.Collect_Rank_KEY);
        assertFalse(RedisContent.Collect_Rank_KEY.contains("{"));
    }

    @Test
    @DisplayName("物理 key 名的怪形状是刻意保留的，不是笔误 —— 改名等于让当天序列重开")
    void legacyKeyNamesAreFrozenOnPurpose() {
        // RedisWorker 拼的是 ID_Seq_KEY + keyPrefix + ":" + 日期，所以 ID_Seq_KEY 自带的那个尾冒号
        // 与中间这个 ":" 拼在一起，就是真实 key 上那个看起来像笔误的双冒号
        assertEquals("icr:", RedisContent.ID_Seq_KEY);
        assertEquals("order:no:", RedisContent.Order_NO_KEY);
        assertEquals("icr:order:no::2026-09-30",
                RedisContent.ID_Seq_KEY + RedisContent.Order_NO_KEY + ":" + "2026-09-30");
        // 浏览量增量刻意是一张 hash 而不是 goods:view:{id} 一堆 key：落库任务要能一次 HGETALL 枚举完
        assertEquals("goods:view:delta", RedisContent.Goods_View_Delta_KEY);
    }
}
