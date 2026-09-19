package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.campus.trade.bean.entry.User;
import com.campus.trade.bean.DTO.request.user.UserChangePasswordDTO;
import com.campus.trade.bean.DTO.request.user.UserLoginDTO;
import com.campus.trade.bean.DTO.request.user.UserRegisterDTO;
import com.campus.trade.bean.DTO.request.user.UserUpdateDTO;
import com.campus.trade.bean.vo.UserProfileVo;
import com.campus.trade.bean.vo.UserVo;

public interface UserService extends IService<User> {
    //注册
    void register(UserRegisterDTO userRegisterDTO);
    //登录
    String login(UserLoginDTO userLoginDTO);
    //退出登录
    void logout(String token);
    //获取当前登录用户信息
    UserVo getUserInfo(Long userId);
    //修改个人资料
    void updateUserInfo(Long userId, UserUpdateDTO userUpdateDTO);
    //修改密码
    void changePassword(Long userId, UserChangePasswordDTO vo);
    //查看他人主页
    UserProfileVo getUserProfile(Long userId);
}
