package com.campus.trade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.DTO.request.collect.CollectAddDTO;
import com.campus.trade.bean.DTO.result.MyResult;
import com.campus.trade.bean.utils.Log;
import com.campus.trade.bean.vo.CollectVo;
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
    @Log("添加收藏")
    public MyResult<String> addCollect(@Valid @RequestBody CollectAddDTO collectAddDTO, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        collectService.addCollect(loginUserId, collectAddDTO.getGoodsId());
        return MyResult.success("收藏成功");
    }

    @Operation(summary = "取消收藏")
    @DeleteMapping("/cancel")
    @Log("取消收藏")
    public MyResult<String> cancelCollect(@Valid @RequestBody CollectAddDTO collectAddDTO, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        collectService.cancelCollect(loginUserId, collectAddDTO.getGoodsId());
        return MyResult.success("取消收藏成功");
    }

    @Operation(summary = "判断是否收藏")
    @GetMapping("/isCollect")
    public MyResult<Boolean> isCollect(@RequestParam("id") Long goodsId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(collectService.isCollect(loginUserId, goodsId));
    }

    @Operation(summary = "获取收藏列表(分页)", description = "按收藏时间倒序，返回商品快照信息")
    @GetMapping("/mylist")
    public MyResult<Page<CollectVo>> getCollectList(@RequestParam(defaultValue = "1") Integer pageNum,
                                                    @RequestParam(defaultValue = "10") Integer pageSize,
                                                    HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(collectService.getCollectList(loginUserId, pageNum, pageSize));
    }
}
