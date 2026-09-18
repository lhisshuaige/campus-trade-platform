package com.campus.trade.service.imp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.entry.Goods;
import com.campus.trade.bean.vo.request.goods.GoodsAddVo;
import com.campus.trade.bean.vo.request.goods.GoodsPageQueryVo;
import com.campus.trade.bean.vo.request.goods.GoodsUpdateVo;
import com.campus.trade.mapper.GoodsMapper;
import com.campus.trade.service.GoodsService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class GoodsServiceImp extends ServiceImpl<GoodsMapper, Goods> implements GoodsService {

    //添加商品
    @Override
    public void addGoods(GoodsAddVo goodsAddVo, Long loginUserId) {
        Goods goods = new Goods();
        BeanUtils.copyProperties(goodsAddVo, goods);
        //绑定用户id
        goods.setUserId(loginUserId);
        //设置商品状态
        goods.setStatus(1);
        save(goods);
    }

    //修改商品
    @Override
    public void updateGoods(GoodsUpdateVo goodsUpdateVo, Long loginUserId) {
        Goods goods = new Goods();
        BeanUtils.copyProperties(goodsUpdateVo, goods);
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
    public Page<Goods> getGoodsByOwner(GoodsPageQueryVo goodsPageQueryVo, Long loginUserId) {
        Page<Goods> page1 = new Page<>(goodsPageQueryVo.getPageNum(), goodsPageQueryVo.getPageSize());
        LambdaQueryWrapper<Goods> queryWrapper = new LambdaQueryWrapper<>();
        //对商品进行属于当前用户分类筛选，标题模糊匹配，展示商品，按照添加时间进行倒序排序
        queryWrapper.eq(Goods::getUserId,loginUserId);//判断是否属于当前用户
        if(goodsPageQueryVo.getCategoryId()!=null){
            queryWrapper.eq(Goods::getCategoryId,goodsPageQueryVo.getCategoryId());
        }
        //标题模糊匹配
        if(goodsPageQueryVo.getKeyword()!=null){
            queryWrapper.like(Goods::getTitle,goodsPageQueryVo.getKeyword());
        }
        queryWrapper.orderByDesc(Goods::getCreateTime);
        return page(page1, queryWrapper);
    }

    //获取所有商品
    @Override
    public Page<Goods> getAllGoods(GoodsPageQueryVo goodsPageQueryVo) {
      Page<Goods> page1 = new Page<>(goodsPageQueryVo.getPageNum(), goodsPageQueryVo.getPageSize());
        LambdaQueryWrapper<Goods> queryWrapper = new LambdaQueryWrapper<>();
      //对商品进行分类筛选，标题模糊匹配，展示上架商品（1表示上架），按照添加时间进行倒序排序
        if(goodsPageQueryVo.getCategoryId()!=null){
            queryWrapper.eq(Goods::getCategoryId,goodsPageQueryVo.getCategoryId());
        }
        //标题模糊匹配
        if(goodsPageQueryVo.getKeyword()!=null){
            queryWrapper.like(Goods::getTitle,goodsPageQueryVo.getKeyword());
        }
        //判断属于哪个用户
        if(goodsPageQueryVo.getUserId()!=null){
            queryWrapper.eq(Goods::getUserId,goodsPageQueryVo.getUserId());
        }
        queryWrapper.eq(Goods::getStatus,1);
        queryWrapper.orderByDesc(Goods::getCreateTime);

        return page(page1, queryWrapper);
    }

    //获取商品详情
    @Override
    public Goods getGoodsDetail(Long GoodsId) {
        Goods goods = getById(GoodsId);
        if(goods==null){
            throw new BusinessException(ErrorCode.NOT_FOUND,"商品不存在,无法获取详细信息");
        }
        return goods;
    }

    //按收藏数从高到低排行
    @Override
    public List<Goods> getCollectRank(int limit) {
        if (limit <= 0 || limit > 50) {
            limit = 10;
        }
        LambdaQueryWrapper<Goods> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Goods::getStatus, 1)   // 只统计在售商品，如需要可去掉
               .orderByDesc(Goods::getCollectCount)
               .orderByDesc(Goods::getCreateTime)
               .last("LIMIT " + limit);   // limit 已做范围校验，无注入风险
        return list(wrapper);
    }
}
