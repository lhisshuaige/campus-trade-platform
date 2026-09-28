package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.DTO.request.goods.GoodsAddDTO;
import com.campus.trade.bean.DTO.request.goods.GoodsAdminQueryDTO;
import com.campus.trade.bean.DTO.request.goods.GoodsPageQueryDTO;
import com.campus.trade.bean.DTO.request.goods.GoodsUpdateDTO;
import com.campus.trade.bean.vo.GoodsDetailVo;
import com.campus.trade.bean.vo.GoodsVo;

import java.util.List;

public interface GoodsService {

    void addGoods(GoodsAddDTO goodsAddDTO,Long loginUserId);
    void updateGoods(GoodsUpdateDTO goodsUpdateDTO, Long loginUserId);
    void changeStatus(Long GoodsId, Integer status, Long loginUserId);
    //管理员强制上/下架（内容治理）：少的只是 owner 校验，取值域与 CAS 规则与 owner 路径完全共用
    void forceChangeStatusByAdmin(Long goodsId, Integer status, Long operatorId);
    //获取自身商品（分页查询）
    Page<GoodsVo> getGoodsByOwner(GoodsPageQueryDTO goodsPageQueryDTO,Long loginUserId);
    void deleteGoods(Long GoodsId, Long loginUserId);

    Page<GoodsVo> getAllGoods(GoodsPageQueryDTO goodsPageQueryDTO);
    //管理端治理列表：可跨卖家、可按任意状态筛
    Page<GoodsVo> getGoodsPageForAdmin(GoodsAdminQueryDTO queryDTO);
    //详情聚合卖家信息与当前登录者的归属/收藏标记；loginUserId 可为 null（匿名浏览）
    GoodsDetailVo getGoodsDetail(Long GoodsId, Long loginUserId);
    // 按收藏数从高到低排行榜
    List<GoodsVo> getCollectRank(int limit);
}
