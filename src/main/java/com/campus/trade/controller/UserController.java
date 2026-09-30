package com.campus.trade.controller;

import com.campus.trade.bean.DTO.request.user.UserChangePasswordDTO;
import com.campus.trade.bean.DTO.request.user.UserLoginDTO;
import com.campus.trade.bean.DTO.request.user.UserRegisterDTO;
import com.campus.trade.bean.DTO.request.user.UserUpdateDTO;
import com.campus.trade.bean.DTO.result.MyResult;
import com.campus.trade.bean.utils.Log;
import com.campus.trade.bean.utils.RateLimit;
import com.campus.trade.bean.vo.UserProfileVo;
import com.campus.trade.bean.vo.UserVo;
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
    @Log("用户注册")
    //按 IP 而不按用户：注册时必定没有登录态，而脚本批量注册的特征就是「同一个出口地址连发」
    @RateLimit(maxCount = 5, dimension = RateLimit.Dimension.IP, message = "注册过于频繁，请稍后再试")
    public MyResult<Void> register(@Valid @RequestBody UserRegisterDTO userRegisterDTO){
        userService.register(userRegisterDTO);
        return MyResult.success();
    }

    // 用户登录
    @PostMapping("/login")
    @Operation(summary = "用户登录",description = "根据用户名和密码登录，返回JWT令牌")
    @Log("用户登录")
    //这一层与 P0-9 的「连续失败锁定」不重叠，是两个维度：那个按【用户】计且只在密码错时计数，
    //拿一批正确用户名轮询试探（每账号只错 4 次）它完全不触发；这个按【来源 IP】计，不看结果
    //30 次/分钟是照「一个机房共用一个出口 IP」的场景定的，再紧就会误伤集体上下课时间的正常登录
    @RateLimit(maxCount = 30, dimension = RateLimit.Dimension.IP)
    public MyResult<String> login(@Valid @RequestBody UserLoginDTO userLoginDTO){
        String token = userService.login(userLoginDTO);
        return MyResult.success(token);
    }

    // 退出登录
    @PostMapping("/logout")
    @Operation(summary = "退出登录",description = "将当前token加入黑名单使其失效")
    @Log("退出登录")
    public MyResult<Void> logout(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        userService.logout(token);
        return MyResult.success();
    }

    // 全端登出
    @PostMapping("/logoutAll")
    @Operation(summary = "全端登出", description = "使该账号已签发的全部Token立即失效（含其他设备）；调用后当前设备也需要重新登录")
    // 与 logout/changePassword 同一类：账号安全事件必须留痕，事后才能回答“什么时候从哪里把全部会话收走的”
    @Log("全端登出")
    public MyResult<Void> logoutAll(HttpServletRequest request) {
        // 只取登录态，不接 userId 参数：否则“全端登出别人”就是一个改改数字就能做的事
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        userService.logoutAll(loginUserId);
        return MyResult.success();
    }

    // 获取当前登录用户信息
    @GetMapping("/info")
    @Operation(summary = "获取当前登录用户信息")
    public MyResult<UserVo> getUserInfo(HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        return MyResult.success(userService.getUserInfo(loginUserId));
    }

    // 修改个人资料
    @PostMapping("/update")
    @Operation(summary = "修改个人资料")
    @Log("修改个人资料")
    public MyResult<Void> updateUserInfo(@Valid @RequestBody UserUpdateDTO userUpdateDTO, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        userService.updateUserInfo(loginUserId, userUpdateDTO);
        return MyResult.success();
    }

    // 修改密码
    @PostMapping("/changePassword")
    @Operation(summary = "修改密码")
    // 改密是账号安全的头号事件：登录/登出/改密都要留痕，只校旧密码而不记录，事后无从回答"什么时候被改走的"
    @Log("修改密码")
    public MyResult<Void> changePassword(@Valid @RequestBody UserChangePasswordDTO vo, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        userService.changePassword(loginUserId, vo);
        return MyResult.success();
    }

    // 查看他人主页
    @GetMapping("/profile/{userId}")
    @Operation(summary = "查看他人主页")
    public MyResult<UserProfileVo> getUserProfile(@PathVariable("userId") Long userId) {
        return MyResult.success(userService.getUserProfile(userId));
    }
}
