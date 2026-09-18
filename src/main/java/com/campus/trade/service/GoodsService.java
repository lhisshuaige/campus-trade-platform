package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.entry.Goods;
import com.campus.trade.bean.vo.request.goods.GoodsAddVo;
import com.campus.trade.bean.vo.request.goods.GoodsPageQueryVo;
import com.campus.trade.bean.vo.request.goods.GoodsUpdateVo;

import java.util.List;

public interface GoodsService {

    void addGoods(GoodsAddVo goodsAddVo,Long loginUserId);
    void updateGoods(GoodsUpdateVo goodsUpdateVo, Long loginUserId);
    void changeStatus(Long GoodsId, Integer status, Long loginUserId);
    //获取自身商品（分页查询）
    Page<Goods> getGoodsByOwner(GoodsPageQueryVo goodsPageQueryVo,Long loginUserId);
    void deleteGoods(Long GoodsId, Long loginUserId);

    Page<Goods> getAllGoods(GoodsPageQueryVo goodsPageQueryVo);
    Goods getGoodsDetail(Long GoodsId);
    // 按收藏数从高到低排行榜
    List<Goods> getCollectRank(int limit);
}
