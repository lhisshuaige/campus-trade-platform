package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.DTO.request.goods.GoodsAddDTO;
import com.campus.trade.bean.DTO.request.goods.GoodsPageQueryDTO;
import com.campus.trade.bean.DTO.request.goods.GoodsUpdateDTO;
import com.campus.trade.bean.vo.GoodsDetailVo;
import com.campus.trade.bean.vo.GoodsVo;

import java.util.List;

public interface GoodsService {

    void addGoods(GoodsAddDTO goodsAddDTO,Long loginUserId);
    void updateGoods(GoodsUpdateDTO goodsUpdateDTO, Long loginUserId);
    void changeStatus(Long GoodsId, Integer status, Long loginUserId);
    //获取自身商品（分页查询）
    Page<GoodsVo> getGoodsByOwner(GoodsPageQueryDTO goodsPageQueryDTO,Long loginUserId);
    void deleteGoods(Long GoodsId, Long loginUserId);

    Page<GoodsVo> getAllGoods(GoodsPageQueryDTO goodsPageQueryDTO);
    GoodsDetailVo getGoodsDetail(Long GoodsId);
    // 按收藏数从高到低排行榜
    List<GoodsVo> getCollectRank(int limit);
}
