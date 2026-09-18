package com.campus.trade.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.trade.bean.entry.Role;
import com.campus.trade.bean.entry.User;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.utils.Log;
import com.campus.trade.bean.utils.RequireRole;
import com.campus.trade.bean.vo.result.MyResult;
import com.campus.trade.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
@Tag(name = "管理员接口", description = "仅管理员可访问的用户管理")
@RequireRole(Role.ADMIN)
public class AdminController {

    @Resource
    private UserService userService;

    @GetMapping("/user/page")
    @Operation(summary = "分页查询用户")
    public MyResult<Page<User>> userPage(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<User> page = userService.page(new Page<>(pageNum, pageSize));
        // 脱敏，不返回密码
        page.getRecords().forEach(u -> u.setPassword(null));
        return MyResult.success(page);
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "查看指定用户信息")
    public MyResult<User> getUser(@PathVariable("userId") Long userId) {
        return MyResult.success(userService.getUserProfile(userId));
    }

    @PutMapping("/user/status")
    @Operation(summary = "启用/禁用用户")
    @Log("修改用户状态")
    public MyResult<Void> changeUserStatus(@RequestParam("userId") Long userId,
                                           @RequestParam("status") Integer status) {
        if (status != 0 && status != 1) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "状态值只能为0或1");
        }
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        user.setStatus(status);
        userService.updateById(user);
        return MyResult.success();
    }
}