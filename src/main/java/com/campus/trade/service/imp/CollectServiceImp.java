package com.campus.trade.service.imp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.entry.Collect;
import com.campus.trade.bean.entry.Goods;
import com.campus.trade.bean.entry.Notice;
import com.campus.trade.bean.vo.CollectVo;
import com.campus.trade.bean.utils.CacheUtils;
import com.campus.trade.bean.utils.RedisContent;
import com.campus.trade.mapper.CollectMapper;
import com.campus.trade.mapper.GoodsMapper;
import com.campus.trade.service.CollectService;
import com.campus.trade.service.NoticeService;
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
    //站内信：收藏不产生交易，但它是卖家判断“要不要继续挂/要不要调价”的信号，值得推一份
    @Resource
    private NoticeService noticeService;

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
        //收藏数就是排序键，榜单不失效就会停在旧名次
        cacheUtils.evictAfterCommit(RedisContent.Collect_Rank_KEY);
        //取消收藏不发通知：否则“收藏—取消”可以被人拿来给卖家刷消息，而消息本身没有意义
        noticeService.send(goods.getUserId(), userId, Notice.TYPE_COLLECT,
                "有人收藏了你的商品",
                "商品《" + goods.getTitle() + "》被加入了 TA 的收藏清单", goodsId);
    }

    //取消收藏
    @Override
    public void cancelCollect(Long userId, Long goodsId) {
        LambdaQueryWrapper<Collect> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(Collect::getUserId,userId)
                .eq(Collect::getGoodsId,goodsId);
        //不能用"先 isCollect 判断、再 remove、再无条件 decr"：
        //两次并发取消都能通过判断，但只有一行可删，于是 decr 被执行两次 → collect_count 少 1，
        //而 decrCollectCount 的 collect_count > 0 护栏会把这次多减“吞掉”，让偏差永久固化。
        //改成以 DELETE 的影响行数作为唯一凭证（InnoDB 行锁保证同一行只有一个事务能删到 1 行）
        int rows = baseMapper.delete(wrapper);
        if (rows <= 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"未收藏该商品，取消失效");
        }
        //取消收藏成功，商品收藏数 -1
        goodsMapper.decrCollectCount(goodsId);
        cacheUtils.evictAfterCommit(RedisContent.goodsDetailKey(goodsId));
        cacheUtils.evictAfterCommit(RedisContent.Collect_Rank_KEY);
    }

    //判断是否收藏
    @Override
    public boolean isCollect(Long userId, Long goodsId) {
        LambdaQueryWrapper<Collect> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(Collect::getUserId,userId)
                .eq(Collect::getGoodsId,goodsId);
        return exists(wrapper);
    }

    //我的收藏列表（分页）。直查 DB，不缓存 ——
    //RedisContent 里原本有个从来没人用过的 collect:list: 前缀，这次删掉而不是"留着以后用"：
    //这是"个人维度 + 跨商品"的派生视图，改一次商品标题/价格就得删掉所有收藏过它的用户的 key，
    //失效面不可枚举。能缓存的前提是"写侧能精确算出该删哪些 key"，不满足就不该上缓存
    @Override
    public Page<CollectVo> getCollectList(Long userId, Integer pageNum, Integer pageSize) {
        long current = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        long size = (pageSize == null || pageSize < 1) ? 10 : Math.min(pageSize, 50);
        Page<CollectVo> page = new Page<>(current, size);
        //分页插件把 records/total 写回传进去的同一个 page 对象，所以直接返回它，不用接返回值
        baseMapper.selectCollectVoPageByUserId(page, userId);
        return page;
    }
}
