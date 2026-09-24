package com.campus.trade.service.imp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.entry.Collect;
import com.campus.trade.bean.entry.Goods;
import com.campus.trade.bean.vo.CollectVo;
import com.campus.trade.bean.utils.CacheUtils;
import com.campus.trade.bean.utils.RedisContent;
import com.campus.trade.mapper.CollectMapper;
import com.campus.trade.mapper.GoodsMapper;
import com.campus.trade.service.CollectService;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
@Service
@Transactional
public class CollectServiceImp extends ServiceImpl<CollectMapper, Collect> implements CollectService {

    @Resource
    private GoodsMapper goodsMapper;

    @Resource
    private CacheUtils cacheUtils;

    //添加收藏
    @Override
    public void addCollect(Long userId, Long goodsId) {

        //判断商品是否存在
        Goods goods = goodsMapper.selectById(goodsId);
        if (goods == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND,"商品不存在,无法进行收藏");
        }
        //不能收藏自己的商品
        if (goods.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,"不能收藏自己的商品");
        }
        //判断是否已经收藏
        if (isCollect(userId, goodsId)) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"已经收藏,请勿重新收藏");
        }

        Collect collect = new Collect();
        collect.setUserId(userId);
        collect.setGoodsId(goodsId);
        try {
            save(collect);
        } catch (DuplicateKeyException e) {
            // 并发下唯一约束兜底：重复收藏，不增加计数
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"已经收藏,请勿重新收藏");
        }
        //收藏成功，商品收藏数 +1
        goodsMapper.incrCollectCount(goodsId);
        //collect_count 属于详情缓存字段，不同步失效会一直脏 30 分钟
        cacheUtils.evictAfterCommit(RedisContent.goodsDetailKey(goodsId));
    }

    //取消收藏
    @Override
    public void cancelCollect(Long userId, Long goodsId) {
        //判断是否已经收藏
        if (!isCollect(userId, goodsId)) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"未收藏该商品，取消失效");
        }
        LambdaQueryWrapper<Collect> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(Collect::getUserId,userId)
                .eq(Collect::getGoodsId,goodsId);
        remove(wrapper);
        //取消收藏成功，商品收藏数 -1
        goodsMapper.decrCollectCount(goodsId);
        cacheUtils.evictAfterCommit(RedisContent.goodsDetailKey(goodsId));
    }

    //判断是否收藏
    @Override
    public boolean isCollect(Long userId, Long goodsId) {
        LambdaQueryWrapper<Collect> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(Collect::getUserId,userId)
                .eq(Collect::getGoodsId,goodsId);
        return exists(wrapper);
    }

    @Override
    public List<CollectVo> getCollectList(Long userId) {
        return baseMapper.getCollectVoListByUserId(userId);
    }
}
