package com.campus.trade.service;

import com.campus.trade.bean.DTO.request.category.CategoryAddDTO;
import com.campus.trade.bean.DTO.request.category.CategoryUpdateDTO;
import com.campus.trade.bean.vo.CategoryVo;

import java.util.List;


public interface CategoryService {
    //添加分类 可以不用传id
    void add(CategoryAddDTO categoryAddDTO);
    //修改分类 必须传id
    void update(CategoryUpdateDTO categoryUpdateDTO);
    //删除分类
    void delete(Long id);
    //获取所有分类
    List<CategoryVo> getAllCategory();
}
