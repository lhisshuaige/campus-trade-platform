package com.campus.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.trade.bean.entry.Goods;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GoodsMapper extends BaseMapper<Goods> {

    // 收藏数 +1（数据库层原子自增，避免并发丢失更新）
    @Update("UPDATE goods SET collect_count = collect_count + 1 WHERE id = #{goodsId}")
    int incrCollectCount(@Param("goodsId") Long goodsId);

    // 收藏数 -1（数据库层原子自减，且不会减到负数）
    @Update("UPDATE goods SET collect_count = collect_count - 1 WHERE id = #{goodsId} AND collect_count > 0")
    int decrCollectCount(@Param("goodsId") Long goodsId);
}
