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
    //只留这一个方法：原来"不分页的 List 版 + 分页版"两份 SQL，加字段必然漏一份。
    //⚠ g.deleted = 0 必须自己写：@TableLogic 只保护 MP 自己生成的 SQL，手写 JOIN 不受保护。
    //写在 WHERE 而不是 ON 上是故意的：商品已删时这行收藏就该从清单里消失，
    //而不是留一行 goodsName/goodsId 全 null 的空壳给前端渲染（那是 LEFT JOIN + ON 过滤才会出现的结果）。
    //正常路径下删商品时已经显式清掉了它的收藏行，这一条是防"删商品与并发收藏"那个竞态窗口的最后防线
    @Select("SELECT c.id, c.goods_id AS goodsId, g.title AS goodsName, g.price, " +
            "g.image AS imgUrl, c.create_time AS createTime " +
            "FROM collect c " +
            "LEFT JOIN goods g ON c.goods_id = g.id " +
            "WHERE c.user_id = #{userId} AND g.deleted = 0 ORDER BY c.create_time DESC")
    IPage<CollectVo> selectCollectVoPageByUserId(IPage<CollectVo> page, @Param("userId") Long userId);
}
