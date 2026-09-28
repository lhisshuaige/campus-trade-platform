package com.campus.trade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.utils.Log;
import com.campus.trade.bean.DTO.request.goods.GoodsAddDTO;
import com.campus.trade.bean.DTO.request.goods.GoodsPageQueryDTO;
import com.campus.trade.bean.DTO.request.goods.GoodsUpdateDTO;
import com.campus.trade.bean.DTO.result.MyResult;
import com.campus.trade.bean.vo.GoodsDetailVo;
import com.campus.trade.bean.vo.GoodsVo;
import com.campus.trade.service.GoodsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/goods")
@Tag(name = "商品接口")
public class GoodsController {

    @Resource
    private GoodsService goodsService;

    @PostMapping("/add")
    @Operation(summary = "添加商品")
    @Log("添加商品")
    public MyResult<Void> add(@Valid @RequestBody GoodsAddDTO goodsAddDTO, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        goodsService.addGoods(goodsAddDTO, loginUserId);
        return MyResult.success();
    }

    @PostMapping("/update")
    @Operation(summary = "修改商品")
    @Log("修改商品")
    public MyResult<Void> update(@Valid @RequestBody GoodsUpdateDTO goodsUpdateDTO, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        goodsService.updateGoods(goodsUpdateDTO, loginUserId);
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
    public MyResult<Page<GoodsVo>> getAllByOwner(@Valid GoodsPageQueryDTO goodsPageQueryDTO, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        Page<GoodsVo> page = goodsService.getGoodsByOwner(goodsPageQueryDTO, loginUserId);
        return MyResult.success(page);
    }

    //分页查询
    @GetMapping("/getAll")
    @Operation(summary = "获取所有商品(搜索+分类筛选)")
    public MyResult<Page<GoodsVo>> getAll(@Valid  GoodsPageQueryDTO goodsPageQueryDTO) {
        Page<GoodsVo> page = goodsService.getAllGoods(goodsPageQueryDTO);
        return MyResult.success(page);
    }

    //获取商品详细信息：一次带回卖家昵称/头像 + 我是否收藏过 + 我能否管理
    //（原来前端要为这三个信息再调 /user/profile/{id} 和 /collect/isCollect）
    @GetMapping("/getDetail")
    @Operation(summary = "获取商品详细信息", description = "聚合卖家展示信息；登录时附带 isCollected/isOwner，匿名访问二者为 false")
    public MyResult<GoodsDetailVo> getDetail(@RequestParam("id") Long GoodsId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        GoodsDetailVo goods = goodsService.getGoodsDetail(GoodsId, loginUserId);
        return MyResult.success(goods);
    }

    /**
     * 收藏排行榜（公开）。
     * 之前榜单只有管理端的 /statistics/hotGoods 能看到，而整个 StatisticsController 是类级 @RequireRole(ADMIN)，
     * 普通用户首页想放“热门好物”就得先登录 —— 口径对但入口不对。
     * 两个端点共用 GoodsService.getCollectRank 与同一份 collect:rank 缓存，不存在第二套算法。
     * GoodsVo 只带 id/title/price/imgUrl/collectCount/createTime，不含 userId，公开不泄露隐私
     */
    @GetMapping("/getCollectRank")
    @Operation(summary = "收藏排行榜", description = "按收藏数降序，仅统计在售商品，最多 50 条（公开接口）")
    public MyResult<List<GoodsVo>> getCollectRank(@RequestParam(defaultValue = "10") Integer limit) {
        return MyResult.success(goodsService.getCollectRank(limit));
    }
}
