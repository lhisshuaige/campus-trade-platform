package com.campus.trade.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campus.trade.bean.entry.Collect;
import com.campus.trade.bean.vo.CollectVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CollectMapper extends BaseMapper<Collect> {

    //联表查询 获取用户的收藏商品详细信息（分页版）。
    //只留这一个方法：原来"不分页的 List 版 + 分页版"两份 SQL，加字段必然漏一份
    @Select("SELECT c.id, c.goods_id AS goodsId, g.title AS goodsName, g.price, " +
            "g.image AS imgUrl, c.create_time AS createTime " +
            "FROM collect c " +
            "LEFT JOIN goods g ON c.goods_id = g.id " +
            "WHERE c.user_id = #{userId} ORDER BY c.create_time DESC")
    IPage<CollectVo> selectCollectVoPageByUserId(IPage<CollectVo> page, @Param("userId") Long userId);
}
