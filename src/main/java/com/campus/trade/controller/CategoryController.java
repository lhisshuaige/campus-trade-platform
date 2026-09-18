package com.campus.trade.controller;

import com.campus.trade.bean.entry.Category;
import com.campus.trade.bean.entry.Role;
import com.campus.trade.bean.utils.RequireRole;
import com.campus.trade.bean.vo.request.category.CategoryAddVo;
import com.campus.trade.bean.vo.request.category.CategoryUpdateVo;
import com.campus.trade.bean.vo.result.MyResult;
import com.campus.trade.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "商品分类接口")
@RestController
@RequestMapping("/category")
public class CategoryController {
    @Resource
    private CategoryService categoryService;

    @PostMapping("/add")
    @Operation(summary = "添加分类")
    @RequireRole(Role.ADMIN)
    public MyResult<Void> add(@Valid @RequestBody CategoryAddVo categoryAddVo) {
        categoryService.add(categoryAddVo);
        return MyResult.success();
    }

    @PostMapping("/update")
    @Operation(summary = "修改分类")
    @RequireRole(Role.ADMIN)
    public MyResult<Void> update(@Valid @RequestBody CategoryUpdateVo categoryUpdateVo) {
        categoryService.update(categoryUpdateVo);
        return MyResult.success();
    }

   @DeleteMapping("/delete")
    @Operation(summary = "删除分类")
    @RequireRole(Role.ADMIN)
    public MyResult<Void> delete(@RequestParam("id") Long id) {
        categoryService.delete(id);
        return MyResult.success();
   }

   @GetMapping("/getAll")
    @Operation(summary = "获取所有分类")
    public MyResult<List<Category>> getAll() {
        return MyResult.success(categoryService.getAllCategory());
   }
}
