package com.campus.trade.service.imp;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.entry.Category;
import com.campus.trade.bean.entry.Goods;
import com.campus.trade.bean.utils.CacheUtils;
import com.campus.trade.bean.utils.RedisContent;
import com.campus.trade.bean.DTO.request.category.CategoryAddDTO;
import com.campus.trade.bean.DTO.request.category.CategoryUpdateDTO;
import com.campus.trade.bean.vo.CategoryVo;
import com.campus.trade.mapper.CategoryMapper;
import com.campus.trade.mapper.GoodsMapper;
import com.campus.trade.service.CategoryService;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CategoryServiceImp extends ServiceImpl<CategoryMapper, Category> implements CategoryService {

    @Resource
    private GoodsMapper goodsMapper;

    @Resource
    private CacheUtils cacheUtils;

    // 分类变化极少，TTL 可以给长一点
    private static final Duration CATEGORY_TTL = Duration.ofHours(12);

    //添加分类
    @Override
    @Transactional
    public void add(CategoryAddDTO categoryAddDTO) {
        Category category = new Category();
        BeanUtils.copyProperties(categoryAddDTO, category);
        save(category);
        //删除缓存 因为这里新添加了分类（提交后删，避免事务内删被并发读回填旧值）
        cacheUtils.evictAfterCommit(RedisContent.Category_List_KEY);
    }

    //修改分类
    @Override
    @Transactional
    public void update(CategoryUpdateDTO categoryUpdateDTO) {
        Category category = new Category();
        BeanUtils.copyProperties(categoryUpdateDTO, category);
        updateById(category);
        //删除缓存 因为这里修改了分类
        cacheUtils.evictAfterCommit(RedisContent.Category_List_KEY);
    }

    //删除分类
    @Override
    @Transactional
    public void delete(Long id) {
        //先判断该分类下是否还有商品 有商品就不能删除，没有就可以删除
        LambdaQueryWrapper<Goods> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Goods::getCategoryId, id);
        Long count= goodsMapper.selectCount(queryWrapper);
        if (count > 0) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"该分类下有商品，不能删除");
        }
        removeById(id);
        //删除缓存 因为这里删除了分类
        cacheUtils.evictAfterCommit(RedisContent.Category_List_KEY);
    }

    // 查询所有分类
    @Override
    public List<CategoryVo> getAllCategory() {
        List<Category> categoryList = cacheUtils.getOrLoad(RedisContent.Category_List_KEY, CATEGORY_TTL,
                json -> JSONUtil.toList(json, Category.class), this::loadFromDb);
        //转成CategoryVo返回前端
        return categoryList.stream().map(category -> {
            CategoryVo vo = new CategoryVo();
            BeanUtils.copyProperties(category, vo);
            return vo;
        }).collect(Collectors.toList());
    }

    private List<Category> loadFromDb() {
        LambdaQueryWrapper<Category> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.orderByAsc(Category::getSort);
        return list(queryWrapper);
    }
}
