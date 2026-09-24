package com.campus.trade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.DTO.request.order.OrderCreateDTO;
import com.campus.trade.bean.DTO.result.MyResult;
import com.campus.trade.bean.vo.OrderVo;
import com.campus.trade.service.OrderService;
import com.campus.trade.service.imp.OrderTradeFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/order")
@Tag(name = "订单接口", description = "订单创建、确认、完成、取消")
public class OrderController {

    @Resource
    private OrderService orderService;

    //带并发协议与提交后消息的写操作统一走编排层，由它保证“锁包住事务”
    @Resource
    private OrderTradeFacade orderTradeFacade;

    @PostMapping("/create")
    @Operation(summary = "创建订单（买家下单）")
    public MyResult<Long> create(@Valid @RequestBody OrderCreateDTO vo, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        //返回订单 id：前端拿到才能跳详情页，也免得再查一次“我最近一笔订单”
        return MyResult.success(orderTradeFacade.createOrder(vo, loginUserId));
    }

    @PutMapping("/confirm")
    @Operation(summary = "卖家确认订单")
    public MyResult<Void> confirm(@RequestParam("id") Long orderId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        orderTradeFacade.confirmOrder(orderId, loginUserId);
        return MyResult.success();
    }

    @PutMapping("/complete")
    @Operation(summary = "确认完成交易（买家确认收货）")
    public MyResult<Void> complete(@RequestParam("id") Long orderId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        orderTradeFacade.completeOrder(orderId, loginUserId);
        return MyResult.success();
    }

    @PutMapping("/cancel")
    @Operation(summary = "取消订单")
    public MyResult<Void> cancel(@RequestParam("id") Long orderId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        orderTradeFacade.cancelOrder(orderId, loginUserId);
        return MyResult.success();
    }

    @GetMapping("/myBuyOrders")
    @Operation(summary = "我买到的商品（买家订单列表）")
    public MyResult<Page<OrderVo>> myBuyOrders(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(orderService.getMyBuyOrders(loginUserId, pageNum, pageSize));
    }

    @GetMapping("/mySellOrders")
    @Operation(summary = "我卖出的商品（卖家订单列表）")
    public MyResult<Page<OrderVo>> mySellOrders(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(orderService.getMySellOrders(loginUserId, pageNum, pageSize));
    }
}
