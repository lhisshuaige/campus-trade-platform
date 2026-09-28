package com.campus.trade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.entry.Role;
import com.campus.trade.bean.entry.User;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.utils.Log;
import com.campus.trade.bean.utils.RequireRole;
import com.campus.trade.bean.DTO.request.comment.CommentAdminQueryDTO;
import com.campus.trade.bean.DTO.request.goods.GoodsAdminQueryDTO;
import com.campus.trade.bean.DTO.request.log.OperLogQueryDTO;
import com.campus.trade.bean.DTO.request.order.OrderAdminQueryDTO;
import com.campus.trade.bean.DTO.request.report.ReportAdminQueryDTO;
import com.campus.trade.bean.DTO.result.MyResult;
import com.campus.trade.bean.vo.CommentAdminVo;
import com.campus.trade.bean.vo.GoodsVo;
import com.campus.trade.bean.vo.OperLogVo;
import com.campus.trade.bean.vo.OrderVo;
import com.campus.trade.bean.vo.ReportVo;
import com.campus.trade.bean.vo.UserProfileVo;
import com.campus.trade.bean.vo.UserVo;
import com.campus.trade.service.CommentService;
import com.campus.trade.service.GoodsService;
import com.campus.trade.service.OperLogService;
import com.campus.trade.service.OrderService;
import com.campus.trade.service.ReportService;
import com.campus.trade.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端。类级 @RequireRole(ADMIN) 罩住全部方法，新增端点不会漏权限。
 * 分类维护不在这里做：/category/add·update·delete 本身就标了 @RequireRole(ADMIN)，
 * 入口已经存在，在这里再写一套只有"多一份会漂移的实现"这一个坏处。
 */
@RestController
@RequestMapping("/admin")
@Tag(name = "管理员接口", description = "用户管理 + 商品/评论治理 + 订单查询")
@RequireRole(Role.ADMIN)
public class AdminController {

    @Resource
    private UserService userService;
    @Resource
    private GoodsService goodsService;
    @Resource
    private CommentService commentService;
    @Resource
    private OrderService orderService;
    @Resource
    private ReportService reportService;
    @Resource
    private OperLogService operLogService;

    @GetMapping("/user/page")
    @Operation(summary = "分页查询用户")
    public MyResult<Page<UserVo>> userPage(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<User> page = userService.page(new Page<>(pageNum, pageSize));
        //转成UserVo返回前端，脱敏，不返回密码/tokenVersion等敏感字段
        Page<UserVo> voPage = (Page<UserVo>) page.convert(user -> {
            UserVo vo = new UserVo();
            BeanUtils.copyProperties(user, vo);
            return vo;
        });
        return MyResult.success(voPage);
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "查看指定用户信息")
    public MyResult<UserProfileVo> getUser(@PathVariable("userId") Long userId) {
        return MyResult.success(userService.getUserProfile(userId));
    }

    @PutMapping("/user/status")
    @Operation(summary = "启用/禁用用户", description = "禁用会同时使该用户全部存量 Token 立即失效（全端下线）")
    @Log("修改用户状态")
    public MyResult<Void> changeUserStatus(@RequestParam("userId") Long userId,
                                           @RequestParam("status") Integer status,
                                           HttpServletRequest request) {
        Long operatorId = (Long) request.getAttribute("loginUserId");
        //校验、归属判断、Token 版本自增与缓存失效全部在 Service，Controller 不写业务
        userService.changeUserStatus(userId, status, operatorId);
        return MyResult.success();
    }

    // ==================== 商品治理 ====================

    @GetMapping("/goods/page")
    @Operation(summary = "分页查询商品", description = "可跨卖家、可按 0下架/1在售/2已售出 筛选")
    public MyResult<Page<GoodsVo>> goodsPage(@Valid GoodsAdminQueryDTO queryDTO) {
        return MyResult.success(goodsService.getGoodsPageForAdmin(queryDTO));
    }

    @PutMapping("/goods/status")
    @Operation(summary = "强制上/下架商品", description = "违规商品处置，不校验归属；已售出(2)的商品不可变更，保护在途订单")
    @Log("管理员强制修改商品状态")
    public MyResult<Void> changeGoodsStatus(@RequestParam("id") Long goodsId,
                                            @RequestParam("status") Integer status,
                                            HttpServletRequest request) {
        Long operatorId = (Long) request.getAttribute("loginUserId");
        goodsService.forceChangeStatusByAdmin(goodsId, status, operatorId);
        return MyResult.success();
    }

    // ==================== 评论治理 ====================

    @GetMapping("/comment/page")
    @Operation(summary = "分页查询评论", description = "可按商品/评论人/内容关键字筛选，返回含商品标题与评论人id")
    public MyResult<Page<CommentAdminVo>> commentPage(@Valid CommentAdminQueryDTO queryDTO) {
        return MyResult.success(commentService.getCommentPageForAdmin(queryDTO));
    }

    @DeleteMapping("/comment/delete")
    @Operation(summary = "删除违规评论", description = "与用户删自己评论共用同一条判定，只是放行角色不同")
    @Log("管理员删除评论")
    public MyResult<Void> deleteComment(@RequestParam("id") Long commentId, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        String loginUserRole = (String) request.getAttribute("loginUserRole");
        commentService.deleteComment(commentId, loginUserId, loginUserRole);
        return MyResult.success();
    }

    // ==================== 内容举报 ====================

    @GetMapping("/report/page")
    @Operation(summary = "分页查询举报", description = "默认待处理在前、同一状态内按举报时间倒序；可按类型/状态/举报人筛选")
    public MyResult<Page<ReportVo>> reportPage(@Valid ReportAdminQueryDTO queryDTO) {
        return MyResult.success(reportService.getReportPageForAdmin(queryDTO));
    }

    @PutMapping("/report/handle")
    @Operation(summary = "处置举报", description = "accept=true 认定违规并已处理，false 不成立已驳回；只改状态并通知举报人，删商品/删评论仍走各自的治理入口")
    @Log("处置举报")
    public MyResult<Void> handleReport(@RequestParam("id") Long reportId,
                                       @RequestParam("accept") boolean accept,
                                       HttpServletRequest request) {
        Long operatorId = (Long) request.getAttribute("loginUserId");
        reportService.handleReport(reportId, accept, operatorId);
        return MyResult.success();
    }

    // ==================== 操作日志 ====================

    //这个接口自己不标 @Log：查日志的行为不需要再产生一条日志，否则后台自己会把自己刷满
    @GetMapping("/log/page")
    @Operation(summary = "分页查询操作日志", description = "可按操作人/结果/操作描述/时间范围筛选，按操作时间倒序")
    public MyResult<Page<OperLogVo>> operLogPage(@Valid OperLogQueryDTO queryDTO) {
        return MyResult.success(operLogService.pageForAdmin(queryDTO));
    }

    // ==================== 订单查询 ====================

    @GetMapping("/order/page")
    @Operation(summary = "分页查询订单", description = "可按状态/订单号(精确)/买家/卖家/商品筛选")
    public MyResult<Page<OrderVo>> orderPage(@Valid OrderAdminQueryDTO queryDTO) {
        return MyResult.success(orderService.getOrderPageForAdmin(queryDTO));
    }
}