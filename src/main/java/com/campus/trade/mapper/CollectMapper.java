package com.campus.trade.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.trade.bean.entry.Collect;
import com.campus.trade.bean.vo.request.collect.CollectResponsVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CollectMapper extends BaseMapper<Collect> {

    //联表查询 获取用户的收藏商品的详细信息
    @Select("SELECT c.id, c.goods_id AS goodsId, g.title AS goodsName, g.price, " +
            "g.image AS imgUrl, c.create_time AS createTime " +
            "FROM collect c " +
            "LEFT JOIN goods g ON c.goods_id = g.id " +
            "WHERE c.user_id = #{userId} ORDER BY c.create_time DESC")
    List<CollectResponsVo> getCollectResponsVoListByUserId(@Param("userId") Long userId);



}
