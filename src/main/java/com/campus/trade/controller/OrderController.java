package com.campus.trade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.DTO.request.order.OrderCreateDTO;
import com.campus.trade.bean.DTO.result.MyResult;
import com.campus.trade.bean.utils.Log;
import com.campus.trade.bean.utils.RateLimit;
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
    @Operation(summary = "创建订单（买家下单）", description = "返回完整订单视图，含订单号，前端可直接展示/跳详情")
    @Log("买家下单")
    //下单是全站单位成本最高的一次写：抢商品锁 + 全局订单号 INCR + 库存/状态变更 + 发延迟消息，
    //而且每一环都在占用别的用户的资源。故额度给得比其它写入口都紧
    @RateLimit(maxCount = 5, message = "下单过于频繁，请稍后再试")
    public MyResult<OrderVo> create(@Valid @RequestBody OrderCreateDTO vo, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(orderTradeFacade.createOrder(vo, loginUserId));
    }

    @GetMapping("/getDetail")
    @Operation(summary = "订单详情", description = "仅买卖双方可查看；管理员查订单走 /admin/order/page")
    public MyResult<OrderVo> getDetail(@RequestParam("id") Long orderId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(orderService.getOrderDetail(orderId, loginUserId));
    }

    @PutMapping("/confirm")
    @Operation(summary = "卖家确认订单")
    @Log("卖家确认订单")
    //确认/收货/取消三个动作共用一份额度（key 相同）：刷的是「同一个用户在高频拨订单状态机」，
    //不必给三个端点各算一份预算；不共用的话，脚本把三个接口轮流打就能拿到三倍额度
    @RateLimit(key = "order:action", maxCount = 20)
    public MyResult<Void> confirm(@RequestParam("id") Long orderId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        orderTradeFacade.confirmOrder(orderId, loginUserId);
        return MyResult.success();
    }

    @PutMapping("/complete")
    @Operation(summary = "确认完成交易（买家确认收货）")
    @Log("买家确认收货")
    @RateLimit(key = "order:action", maxCount = 20)
    public MyResult<Void> complete(@RequestParam("id") Long orderId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        orderTradeFacade.completeOrder(orderId, loginUserId);
        return MyResult.success();
    }

    @PutMapping("/cancel")
    @Operation(summary = "取消订单")
    //下单/确认/收货/取消这四个动作会改商品状态与库存，是最需要"谁在什么时候点的"的地方
    @Log("取消订单")
    @RateLimit(key = "order:action", maxCount = 20)
    public MyResult<Void> cancel(@RequestParam("id") Long orderId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        orderTradeFacade.cancelOrder(orderId, loginUserId);
        return MyResult.success();
    }

    @GetMapping("/myBuyOrders")
    @Operation(summary = "我买到的商品（买家订单列表）", description = "status 可选：0待确认/1已确认/2已完成/3已取消")
    public MyResult<Page<OrderVo>> myBuyOrders(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(orderService.getMyBuyOrders(loginUserId, status, pageNum, pageSize));
    }

    @GetMapping("/mySellOrders")
    @Operation(summary = "我卖出的商品（卖家订单列表）", description = "status 可选：0待确认/1已确认/2已完成/3已取消")
    public MyResult<Page<OrderVo>> mySellOrders(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(orderService.getMySellOrders(loginUserId, status, pageNum, pageSize));
    }
}
