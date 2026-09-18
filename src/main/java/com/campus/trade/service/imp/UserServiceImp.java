package com.campus.trade.service.imp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.entry.User;
import com.campus.trade.bean.utils.JwtUtils;
import com.campus.trade.bean.utils.TokenBlacklistUtils;
import com.campus.trade.bean.utils.UserActionLimitUtils;
import com.campus.trade.bean.vo.request.user.UserChangePasswordVo;
import com.campus.trade.bean.vo.request.user.UserLoginVo;
import com.campus.trade.bean.vo.request.user.UserRegisterVo;
import com.campus.trade.bean.vo.request.user.UserUpdateVo;
import com.campus.trade.mapper.UserMapper;
import com.campus.trade.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@Transactional
public class UserServiceImp extends ServiceImpl<UserMapper, User> implements UserService {

    @Resource
    private JwtUtils jwtUtils;

    @Resource
    private TokenBlacklistUtils tokenBlacklistUtils;

    @Resource
    private UserActionLimitUtils userActionLimitUtils;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final int MAX_PER_DAY=100;

    // 用户注册
    @Override
    public void register(UserRegisterVo userRegisterVo) {
        //判断用户名是否已存在
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, userRegisterVo.getUsername());
        User exsituser = getOne(queryWrapper);
        if(exsituser != null){
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"用户名已存在");
        }
        User user = new User();
        BeanUtils.copyProperties(userRegisterVo, user);
        //密码BCrypt加密存储，不能明文入库
        user.setPassword(passwordEncoder.encode(userRegisterVo.getPassword()));
        //设置状态 默认普通用户 正常状态
        user.setStatus(1);
        user.setRole("user");

        //保存用户
        save(user);
    }

    // 用户登录
    @Override
    public String login(UserLoginVo userLoginVo) {
        LambdaQueryWrapper<User> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername,userLoginVo.getUsername());
        User exsituser = getOne(wrapper);
        if(exsituser == null){
            throw new BusinessException(ErrorCode.PARAM_ERROR,"用户名不存在");
        }
        //判断密码是否正确(BCrypt匹配)
        if(!passwordEncoder.matches(userLoginVo.getPassword(), exsituser.getPassword())){
            throw new BusinessException(ErrorCode.PARAM_ERROR,"密码错误");
        }
        //判断是否被禁用
        if(exsituser.getStatus() == 0){
            throw new BusinessException(ErrorCode.FORBIDDEN,"用户被禁用");
        }
        //每日登录/登出次数限制（5次/天）
        if(!userActionLimitUtils.tryAcquire(exsituser.getId(), MAX_PER_DAY)){
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,"今日登录/登出次数已达上限,请明天再试");
        }
        return jwtUtils.generateToken(exsituser.getId(), exsituser.getUsername(), exsituser.getRole());
    }

    // 退出登录：把当前 token 加入黑名单，使其立即失效
    @Override
    public void logout(String token) {
        //计入当日次数（登出本身始终放行，避免用户被锁死）
        userActionLimitUtils.tryAcquire(jwtUtils.getUserId(token), MAX_PER_DAY);
        tokenBlacklistUtils.add(token);
    }

    //获取当前登录用户信息
    @Override
    public User getUserInfo(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        //脱敏，不返回密码
        user.setPassword(null);
        return user;
    }

    //修改个人资料
    @Override
    public void updateUserInfo(Long userId, UserUpdateVo vo) {
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (vo.getNickname() != null) {
            user.setNickname(vo.getNickname());
        }
        if (vo.getPhone() != null) {
            user.setPhone(vo.getPhone());
        }
        if (vo.getAvatar() != null) {
            user.setAvatar(vo.getAvatar());
        }
        updateById(user);
    }

    //修改密码
    @Override
    public void changePassword(Long userId, UserChangePasswordVo vo) {
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (!passwordEncoder.matches(vo.getOldPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "旧密码不正确");
        }
        user.setPassword(passwordEncoder.encode(vo.getNewPassword()));
        updateById(user);
    }

    //查看他人主页
    @Override
    public User getUserProfile(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        //脱敏，不返回密码
        user.setPassword(null);
        return user;
    }
}
