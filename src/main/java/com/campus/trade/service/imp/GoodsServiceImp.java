package com.campus.trade.service.imp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.entry.Goods;
import com.campus.trade.bean.DTO.request.goods.GoodsAddDTO;
import com.campus.trade.bean.DTO.request.goods.GoodsPageQueryDTO;
import com.campus.trade.bean.DTO.request.goods.GoodsUpdateDTO;
import com.campus.trade.bean.vo.GoodsDetailVo;
import com.campus.trade.bean.vo.GoodsVo;
import com.campus.trade.mapper.GoodsMapper;
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

    private static final Duration GOODS_DETAIL_TTL = Duration.ofMinutes(30);

    private String detailKey(Long id) {
        return RedisContent.Goods_Detail_KEY + id;
    }

    //添加商品
    @Override
    public void addGoods(GoodsAddDTO goodsAddDTO, Long loginUserId) {
        Goods goods = new Goods();
        BeanUtils.copyProperties(goodsAddDTO, goods);
        //绑定用户id
        goods.setUserId(loginUserId);
        //设置商品状态
        goods.setStatus(1);
        save(goods);
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
        if(status!=0&&status!=1&&status!=2){
            throw new BusinessException(ErrorCode.PARAM_ERROR,"商品状态错误,请检查");
        }
        goods.setStatus(status);
        updateById(goods);
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
        removeById(GoodsId);
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
        queryWrapper.eq(Goods::getStatus,1);
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
        wrapper.eq(Goods::getStatus, 1)   // 只统计在售商品，如需要可去掉
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
