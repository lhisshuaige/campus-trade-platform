package com.campus.trade.service;

import com.campus.trade.bean.vo.CollectVo;

import java.util.List;

public interface CollectService {
    void addCollect(Long userId, Long goodsId);
    void cancelCollect(Long userId, Long goodsId);
    boolean isCollect(Long userId, Long goodsId);
    List<CollectVo> getCollectList(Long userId);

}
