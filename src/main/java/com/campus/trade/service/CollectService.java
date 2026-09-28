package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.vo.CollectVo;

public interface CollectService {
    void addCollect(Long userId, Long goodsId);
    void cancelCollect(Long userId, Long goodsId);
    boolean isCollect(Long userId, Long goodsId);
    //我的收藏列表（分页，直查 DB 不缓存，理由见 CollectServiceImp）
    Page<CollectVo> getCollectList(Long userId, Integer pageNum, Integer pageSize);

}
