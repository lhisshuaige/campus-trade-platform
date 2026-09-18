package com.campus.trade.service;

import com.campus.trade.bean.entry.Category;
import com.campus.trade.bean.vo.request.category.CategoryAddVo;
import com.campus.trade.bean.vo.request.category.CategoryUpdateVo;

import java.util.List;


public interface CategoryService {
    //添加分类 可以不用传id
    void add(CategoryAddVo categoryAddVo);
    //修改分类 必须传id
    void update(CategoryUpdateVo categoryUpdateVo);
    //删除分类
    void delete(Long id);
    //获取所有分类
    List<Category> getAllCategory();
}
