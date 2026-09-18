package com.campus.trade.controller;

import com.campus.trade.bean.vo.request.collect.CollectAddVo;
import com.campus.trade.bean.vo.request.collect.CollectResponsVo;
import com.campus.trade.bean.vo.result.MyResult;
import com.campus.trade.service.CollectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "收藏接口")
@RestController
@RequestMapping("/collect")
public class CollectController {

    @Resource
    private CollectService collectService;

    @Operation(summary = "添加收藏")
    @PostMapping("/add")
    public MyResult<String> addCollect(@Valid @RequestBody CollectAddVo collectAddVo, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        collectService.addCollect(loginUserId, collectAddVo.getGoodsId());
        return MyResult.success("收藏成功");
    }

    @Operation(summary = "取消收藏")
    @DeleteMapping("/cancel")
    public MyResult<String> cancelCollect(@Valid @RequestBody CollectAddVo collectAddVo, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        collectService.cancelCollect(loginUserId, collectAddVo.getGoodsId());
        return MyResult.success("取消收藏成功");
    }

    @Operation(summary = "判断是否收藏")
    @GetMapping("/isCollect")
    public MyResult<Boolean> isCollect(@RequestParam("id") Long goodsId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(collectService.isCollect(loginUserId, goodsId));
    }

    @Operation(summary = "获取收藏列表")
    @GetMapping("/mylist")
    public MyResult<List<CollectResponsVo>> getCollectList(HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(collectService.getCollectList(loginUserId));
    }
}
