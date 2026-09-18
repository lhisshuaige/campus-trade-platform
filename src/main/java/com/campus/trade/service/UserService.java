package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.campus.trade.bean.entry.User;
import com.campus.trade.bean.vo.request.user.UserChangePasswordVo;
import com.campus.trade.bean.vo.request.user.UserLoginVo;
import com.campus.trade.bean.vo.request.user.UserRegisterVo;
import com.campus.trade.bean.vo.request.user.UserUpdateVo;

public interface UserService extends IService<User> {
    //注册
    void register(UserRegisterVo userRegisterVo);
    //登录
    String login(UserLoginVo userLoginVo);
    //退出登录
    void logout(String token);
    //获取当前登录用户信息
    User getUserInfo(Long userId);
    //修改个人资料
    void updateUserInfo(Long userId, UserUpdateVo userUpdateVo);
    //修改密码
    void changePassword(Long userId, UserChangePasswordVo vo);
    //查看他人主页
    User getUserProfile(Long userId);
}
