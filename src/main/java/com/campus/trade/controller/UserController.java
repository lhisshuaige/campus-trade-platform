package com.campus.trade.controller;

import com.campus.trade.bean.entry.User;
import com.campus.trade.bean.vo.request.user.UserChangePasswordVo;
import com.campus.trade.bean.vo.request.user.UserLoginVo;
import com.campus.trade.bean.vo.request.user.UserRegisterVo;
import com.campus.trade.bean.vo.request.user.UserUpdateVo;
import com.campus.trade.bean.vo.result.MyResult;
import com.campus.trade.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
@Tag(name = "用户接口", description = "用户登录，注册相关接口")
public class UserController {

    @Resource
    private UserService userService;

    // 用户注册
    @PostMapping("/register")
    @Operation(summary = "用户注册",description = "根据用户名和密码注册新用户")
    public MyResult<Void> register(@Valid @RequestBody UserRegisterVo userRegisterVo){
        userService.register(userRegisterVo);
        return MyResult.success();
    }

    // 用户登录
    @PostMapping("/login")
    @Operation(summary = "用户登录",description = "根据用户名和密码登录，返回JWT令牌")
    public MyResult<String> login(@Valid @RequestBody UserLoginVo userLoginVo){
        String token = userService.login(userLoginVo);
        return MyResult.success(token);
    }

    // 退出登录
    @PostMapping("/logout")
    @Operation(summary = "退出登录",description = "将当前token加入黑名单使其失效")
    public MyResult<Void> logout(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        userService.logout(token);
        return MyResult.success();
    }

    // 获取当前登录用户信息
    @GetMapping("/info")
    @Operation(summary = "获取当前登录用户信息")
    public MyResult<User> getUserInfo(HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(userService.getUserInfo(loginUserId));
    }

    // 修改个人资料
    @PostMapping("/update")
    @Operation(summary = "修改个人资料")
    public MyResult<Void> updateUserInfo(@Valid @RequestBody UserUpdateVo userUpdateVo, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        userService.updateUserInfo(loginUserId, userUpdateVo);
        return MyResult.success();
    }

    // 修改密码
    @PostMapping("/changePassword")
    @Operation(summary = "修改密码")
    public MyResult<Void> changePassword(@Valid @RequestBody UserChangePasswordVo vo, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        userService.changePassword(loginUserId, vo);
        return MyResult.success();
    }

    // 查看他人主页
    @GetMapping("/profile/{userId}")
    @Operation(summary = "查看他人主页")
    public MyResult<User> getUserProfile(@PathVariable("userId") Long userId) {
        return MyResult.success(userService.getUserProfile(userId));
    }
}
