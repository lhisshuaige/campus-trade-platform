package com.campus.trade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.entry.Goods;
import com.campus.trade.bean.utils.Log;
import com.campus.trade.bean.vo.request.goods.GoodsAddVo;
import com.campus.trade.bean.vo.request.goods.GoodsPageQueryVo;
import com.campus.trade.bean.vo.request.goods.GoodsUpdateVo;
import com.campus.trade.bean.vo.result.MyResult;
import com.campus.trade.service.GoodsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/goods")
@Tag(name = "商品接口")
public class GoodsController {

    @Resource
    private GoodsService goodsService;

    @PostMapping("/add")
    @Operation(summary = "添加商品")
    @Log("添加商品")
    public MyResult<Void> add(@Valid @RequestBody GoodsAddVo goodsAddVo, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        goodsService.addGoods(goodsAddVo, loginUserId);
        return MyResult.success();
    }

    @PostMapping("/update")
    @Operation(summary = "修改商品")
    @Log("修改商品")
    public MyResult<Void> update(@Valid @RequestBody GoodsUpdateVo goodsUpdateVo, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        goodsService.updateGoods(goodsUpdateVo, loginUserId);
        return MyResult.success();
    }

    @PutMapping("/changeStatus")
    @Operation(summary = "修改商品状态,商品上下架")
    @Log("修改商品状态")
    public MyResult<Void> changeStatus(@RequestParam("id") Long GoodsId,
                                       @RequestParam("status") Integer status,
                                        HttpServletRequest  request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        goodsService.changeStatus(GoodsId, status, loginUserId);
        return MyResult.success();
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除商品")
    @Log("删除商品")
    public MyResult<Void> delete(@RequestParam("id") Long id, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        goodsService.deleteGoods(id, loginUserId);
        return MyResult.success();
    }

    // 获取当前用户所有商品
    @GetMapping("/getAllByOwner")
    @Operation(summary = "获取当前用户所有商品")
    public MyResult<Page<Goods>> getAllByOwner(@Valid GoodsPageQueryVo goodsPageQueryVo, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        Page<Goods> page = goodsService.getGoodsByOwner(goodsPageQueryVo, loginUserId);
        return MyResult.success(page);
    }

    //分页查询
    @GetMapping("/getAll")
    @Operation(summary = "获取所有商品(搜索+分类筛选)")
    public MyResult<Page<Goods>> getAll(@Valid  GoodsPageQueryVo goodsPageQueryVo) {
        Page<Goods> page = goodsService.getAllGoods(goodsPageQueryVo);
        return MyResult.success(page);
    }

    //获取商品详细信息
    @GetMapping("/getDetail")
    @Operation(summary = "获取商品详细信息")
    public MyResult<Goods> getDetail(@RequestParam("id") Long GoodsId) {
        Goods goods = goodsService.getGoodsDetail(GoodsId);
        return MyResult.success(goods);
    }
}
