package com.campus.trade.service.imp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.entry.Order;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.entry.Goods;
import com.campus.trade.bean.DTO.request.goods.GoodsAddDTO;
import com.campus.trade.bean.DTO.request.goods.GoodsPageQueryDTO;
import com.campus.trade.bean.DTO.request.goods.GoodsUpdateDTO;
import com.campus.trade.bean.vo.GoodsDetailVo;
import com.campus.trade.bean.vo.GoodsVo;
import com.campus.trade.mapper.GoodsMapper;
import com.campus.trade.mapper.OrderMapper;
import com.campus.trade.service.GoodsService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.utils.CacheUtils;
import com.campus.trade.bean.utils.RedisContent;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class GoodsServiceImp extends ServiceImpl<GoodsMapper, Goods> implements GoodsService {

    @Resource
    private CacheUtils cacheUtils;

    //只用于"删除商品前预检是否已有订单引用"，注入 Mapper 而不是 Service，避免和 OrderServiceImp 形成循环依赖
    @Resource
    private OrderMapper orderMapper;

    private static final Duration GOODS_DETAIL_TTL = Duration.ofMinutes(30);

    private String detailKey(Long id) {
        return RedisContent.goodsDetailKey(id);
    }

    //添加商品
    @Override
    public void addGoods(GoodsAddDTO goodsAddDTO, Long loginUserId) {
        Goods goods = new Goods();
        BeanUtils.copyProperties(goodsAddDTO, goods);
        //绑定用户id
        goods.setUserId(loginUserId);
        //设置商品状态 新发布默认在售
        goods.setStatus(Goods.STATUS_ON_SALE);
        save(goods);
        //新商品 id 可能命中过"空值缓存(防穿透)"，这里一并失效
        cacheUtils.evictAfterCommit(detailKey(goods.getId()));
    }

    //修改商品
    @Override
    public void updateGoods(GoodsUpdateDTO goodsUpdateDTO, Long loginUserId) {
        Goods goods = new Goods();
        BeanUtils.copyProperties(goodsUpdateDTO, goods);
        //判断这个商品是否存在 如果存在再判断是否属于当前用户
        Goods existsGoods = getById(goods.getId());
        if(existsGoods==null){
            throw new BusinessException(ErrorCode.NOT_FOUND,"商品不存在,无法修改");
        }
        if(!existsGoods.getUserId().equals(loginUserId)){
            throw new BusinessException(ErrorCode.FORBIDDEN,"商品不属于当前用户,无法修改");
        }
        updateById(goods);
        //写库后失效详情缓存，否则脏读窗口最长 30 分钟
        cacheUtils.evictAfterCommit(detailKey(goods.getId()));
    }

    //修改商品状态
    @Override
    public void changeStatus(Long GoodsId, Integer status, Long loginUserId) {
        Goods goods=getById(GoodsId);
        if(goods==null){
            throw new BusinessException(ErrorCode.NOT_FOUND,"商品不存在,无法修改商品状态");
        }
        if(!goods.getUserId().equals(loginUserId)){
            throw new BusinessException(ErrorCode.FORBIDDEN,"商品不属于当前用户,无法修改商品状态");
        }
        //取值域必须与 init.sql 的 chk_goods_status 一致，用常量避免两处漂移
        //手工只能 上架(1)/下架(0)；已售出(2) 由订单占用，只能由下单/取消流程维护
        //放开 2 等于允许卖家手工“宣布售出”，或把在途订单的商品捞回在售 → 一物多卖
        if(status==null||(status!=Goods.STATUS_OFF&&status!=Goods.STATUS_ON_SALE)){
            throw new BusinessException(ErrorCode.PARAM_ERROR,"只能上架(1)或下架(0)，售出状态由系统维护");
        }
        // CAS：排除“已售出”。原来用 updateById 是按主键无脑覆盖，卖家一次上架就能击穿下单流程
        //用 update(null, wrapper) 只 SET status 一列，冲突面比写回整份快照小
        LambdaUpdateWrapper<Goods> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Goods::getId, GoodsId)
                .ne(Goods::getStatus, Goods.STATUS_SOLD)
                .set(Goods::getStatus, status);
        if (baseMapper.update(null, wrapper) == 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "商品已售出或状态已变更，无法修改");
        }
        //上架/下架都会影响详情页展示
        cacheUtils.evictAfterCommit(detailKey(GoodsId));
    }

    //删除商品
    @Override
    public void deleteGoods(Long GoodsId, Long loginUserId) {
        Goods goods = getById(GoodsId);
        //判断商品是否存在
        if(goods==null){
            throw new BusinessException(ErrorCode.NOT_FOUND,"商品不存在,无法删除");
        }
        if(!goods.getUserId().equals(loginUserId)){
            throw new BusinessException(ErrorCode.FORBIDDEN,"商品不属于当前用户,无法删除");
        }
        //FK fk_order_goods 是 ON DELETE RESTRICT：有订单引用时数据库会直接报错，
        //不预检就删会抛 DataIntegrityViolationException 落到全局兜底 500，用户看不懂。
        //这里与"分类下有商品禁止删除"同理：DB 约束是最后防线，Service 预检负责给出人话。
        Long orderCount = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getGoodsId, GoodsId));
        if (orderCount != null && orderCount > 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "该商品已产生交易记录，不可删除，如需隐藏请改为下架");
        }
        removeById(GoodsId);
        //商品已删除，缓存不清掉详情页还能展示 30 分钟
        cacheUtils.evictAfterCommit(detailKey(GoodsId));
    }
    //获取自身商品（分页查询）
    @Override
    public Page<GoodsVo> getGoodsByOwner(GoodsPageQueryDTO goodsPageQueryDTO, Long loginUserId) {
        Page<Goods> page1 = new Page<>(goodsPageQueryDTO.getPageNum(), goodsPageQueryDTO.getPageSize());
        LambdaQueryWrapper<Goods> queryWrapper = new LambdaQueryWrapper<>();
        //对商品进行属于当前用户分类筛选，标题模糊匹配，展示商品，按照添加时间进行倒序排序
        queryWrapper.eq(Goods::getUserId,loginUserId);//判断是否属于当前用户
        if(goodsPageQueryDTO.getCategoryId()!=null){
            queryWrapper.eq(Goods::getCategoryId,goodsPageQueryDTO.getCategoryId());
        }
        //标题模糊匹配
        if(goodsPageQueryDTO.getKeyword()!=null){
            queryWrapper.like(Goods::getTitle,goodsPageQueryDTO.getKeyword());
        }
        queryWrapper.orderByDesc(Goods::getCreateTime);
        Page<Goods> goodsPage = page(page1, queryWrapper);
        //转成GoodsVo返回前端
        return (Page<GoodsVo>) goodsPage.convert(goods -> {
            GoodsVo vo = new GoodsVo();
            BeanUtils.copyProperties(goods, vo);
            return vo;
        });
    }

    //获取所有商品
    @Override
    public Page<GoodsVo> getAllGoods(GoodsPageQueryDTO goodsPageQueryDTO) {
        Page<Goods> page1 = new Page<>(goodsPageQueryDTO.getPageNum(), goodsPageQueryDTO.getPageSize());
        LambdaQueryWrapper<Goods> queryWrapper = new LambdaQueryWrapper<>();
      //对商品进行分类筛选，标题模糊匹配，展示上架商品（1表示上架），按照添加时间进行倒序排序
        if(goodsPageQueryDTO.getCategoryId()!=null){
            queryWrapper.eq(Goods::getCategoryId,goodsPageQueryDTO.getCategoryId());
        }
        //标题模糊匹配
        if(goodsPageQueryDTO.getKeyword()!=null){
            queryWrapper.like(Goods::getTitle,goodsPageQueryDTO.getKeyword());
        }
        //判断属于哪个用户
        if(goodsPageQueryDTO.getUserId()!=null){
            queryWrapper.eq(Goods::getUserId,goodsPageQueryDTO.getUserId());
        }
        queryWrapper.eq(Goods::getStatus, Goods.STATUS_ON_SALE);
        queryWrapper.orderByDesc(Goods::getCreateTime);
        Page<Goods> goodsPage = page(page1, queryWrapper);
        //转成GoodsVo返回前端
        return (Page<GoodsVo>) goodsPage.convert(goods -> {
                    GoodsVo vo = new GoodsVo();
                    BeanUtils.copyProperties(goods, vo);
                    return vo;
                });
    }

    //获取商品详情
    @Override
    public GoodsDetailVo getGoodsDetail(Long GoodsId) {
        Goods goods = cacheUtils.getOrLoad(detailKey(GoodsId), GOODS_DETAIL_TTL,
                Goods.class, () -> getById(GoodsId));
        if (goods == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在,无法获取详细信息");
        }
        //转成GoodsDetailVo返回前端,避免泄露userId等内部字段
        GoodsDetailVo vo = new GoodsDetailVo();
        BeanUtils.copyProperties(goods, vo);
        return vo;
    }

    //按收藏数从高到低排行
    @Override
    public List<GoodsVo> getCollectRank(int limit) {
        if (limit <= 0 || limit > 50) {
            limit = 10;
        }
        LambdaQueryWrapper<Goods> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Goods::getStatus, Goods.STATUS_ON_SALE)   // 只统计在售商品，如需要可去掉
               .orderByDesc(Goods::getCollectCount)
               .orderByDesc(Goods::getCreateTime)
               .last("LIMIT " + limit);   // limit 已做范围校验，无注入风险
        //转成GoodsVo返回前端
        return list(wrapper).stream().map(goods -> {
            GoodsVo vo = new GoodsVo();
            BeanUtils.copyProperties(goods, vo);
            return vo;
        }).collect(Collectors.toList());
    }
}
