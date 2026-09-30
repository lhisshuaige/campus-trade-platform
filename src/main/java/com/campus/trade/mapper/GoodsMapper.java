package com.campus.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.trade.bean.entry.Goods;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface GoodsMapper extends BaseMapper<Goods> {

    // 收藏数 +1（数据库层原子自增，避免并发丢失更新）
    @Update("UPDATE goods SET collect_count = collect_count + 1 WHERE id = #{goodsId}")
    int incrCollectCount(@Param("goodsId") Long goodsId);

    // 收藏数 -1（数据库层原子自减，且不会减到负数）
    @Update("UPDATE goods SET collect_count = collect_count - 1 WHERE id = #{goodsId} AND collect_count > 0")
    int decrCollectCount(@Param("goodsId") Long goodsId);

    // 浏览量存量累加（落库任务专用）：同样走数据库原子自增，
    // 不读旧值再写回 —— 否则并发的 +delta 会互相覆盖（和 collect_count 同一条教训）
    @Update("UPDATE goods SET view_count = view_count + #{delta} WHERE id = #{goodsId}")
    int incrViewCount(@Param("goodsId") Long goodsId, @Param("delta") long delta);

    // 对账用：找出冗余列与 collect 表真实条数不一致的商品（collect 表才是唯一真相源）。
    // g.deleted = 0 必须写：已逻辑删除的商品其 collect_count 不再对外可见，把它捞进差集会
    // 让校准任务天天去 UPDATE 一行没人读的数据，还会把“发现漂移”这个告警刷到不可用
    //（告警被噪声吞掉比没有告警更糟），而它的 collect 行已在删商品时被清掉，真值永远是 0
    @Select("SELECT g.id AS goodsId, IFNULL(c.cnt, 0) AS realCount, g.collect_count AS wrongCount " +
            "FROM goods g LEFT JOIN (SELECT goods_id, COUNT(*) AS cnt FROM collect GROUP BY goods_id) c " +
            "  ON c.goods_id = g.id " +
            "WHERE g.collect_count <> IFNULL(c.cnt, 0) AND g.deleted = 0 " +
            "LIMIT #{limit}")
    List<Map<String, Object>> selectCollectCountDrift(@Param("limit") int limit);

    // 对账用：带 CAS 的覆写 —— 仅当冗余列仍等于对账时的旧值才改，
    // 命中 0 行说明这期间有并发收藏，交给下一轮，绝不吃掉用户的 +1/-1
    @Update("UPDATE goods SET collect_count = #{realCount} " +
            "WHERE id = #{goodsId} AND collect_count = #{wrongCount}")
    int fixCollectCount(@Param("goodsId") Long goodsId,
                        @Param("realCount") int realCount,
                        @Param("wrongCount") int wrongCount);
}
