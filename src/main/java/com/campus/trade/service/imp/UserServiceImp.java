package com.campus.trade.service.imp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.entry.User;
import com.campus.trade.bean.utils.JwtUtils;
import com.campus.trade.bean.utils.TokenBlacklistUtils;
import com.campus.trade.bean.utils.TokenVersionUtils;
import com.campus.trade.bean.utils.UserActionLimitUtils;
import com.campus.trade.bean.DTO.request.user.UserChangePasswordDTO;
import com.campus.trade.bean.DTO.request.user.UserLoginDTO;
import com.campus.trade.bean.DTO.request.user.UserRegisterDTO;
import com.campus.trade.bean.DTO.request.user.UserUpdateDTO;
import com.campus.trade.bean.vo.UserProfileVo;
import com.campus.trade.bean.vo.UserVo;
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
    private TokenVersionUtils tokenVersionUtils;

    @Resource
    private UserActionLimitUtils userActionLimitUtils;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final int MAX_PER_DAY=100;

    // 用户注册
    @Override
    public void register(UserRegisterDTO userRegisterDTO) {
        //判断用户名是否已存在
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, userRegisterDTO.getUsername());
        User exsituser = getOne(queryWrapper);
        if(exsituser != null){
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"用户名已存在");
        }
        User user = new User();
        BeanUtils.copyProperties(userRegisterDTO, user);
        //密码BCrypt加密存储，不能明文入库
        user.setPassword(passwordEncoder.encode(userRegisterDTO.getPassword()));
        //设置状态 默认普通用户 正常状态
        user.setStatus(1);
        user.setRole("user");

        //保存用户
        save(user);
    }

    // 用户登录
    @Override
    public String login(UserLoginDTO userLoginDTO) {
        LambdaQueryWrapper<User> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername,userLoginDTO.getUsername());
        User exsituser = getOne(wrapper);
        // 统一错误提示，避免用户名枚举；用户不存在与密码错误返回同一句话
        if(exsituser == null){
            throw new BusinessException(ErrorCode.PARAM_ERROR,"用户名或密码错误");
        }
        if(!passwordEncoder.matches(userLoginDTO.getPassword(), exsituser.getPassword())){
            throw new BusinessException(ErrorCode.PARAM_ERROR,"用户名或密码错误");
        }
        //判断是否被禁用
        if(exsituser.getStatus() == 0){
            throw new BusinessException(ErrorCode.FORBIDDEN,"用户被禁用");
        }
        //每日登录/登出次数限制（5次/天）
        if(!userActionLimitUtils.tryAcquire(exsituser.getId(), MAX_PER_DAY)){
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,"今日登录/登出次数已达上限,请明天再试");
        }
        //Token 中写入用户级版本号，改密后版本自增即可让该 Token 失效
        return jwtUtils.generateToken(exsituser.getId(), exsituser.getUsername(),
                exsituser.getRole(), exsituser.getTokenVersion());
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
    public UserVo getUserInfo(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        //转成UserVo返回前端，避免泄露密码、tokenVersion等敏感字段
        UserVo vo = new UserVo();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }

    //修改个人资料
    @Override
    public void updateUserInfo(Long userId, UserUpdateDTO vo) {
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
    public void changePassword(Long userId, UserChangePasswordDTO vo) {
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (!passwordEncoder.matches(vo.getOldPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "旧密码不正确");
        }
        user.setPassword(passwordEncoder.encode(vo.getNewPassword()));
        //改密后 Token 版本 +1：此前签发的所有旧 Token 版本不再匹配，立即失效
        int newVersion = (user.getTokenVersion() == null ? 1 : user.getTokenVersion()) + 1;
        user.setTokenVersion(newVersion);
        updateById(user);
        //同步刷新缓存，避免拦截器仍读到旧版本号
        tokenVersionUtils.refresh(userId, newVersion);
    }

    //查看他人主页
    @Override
    public UserProfileVo getUserProfile(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        //转成UserProfileVo返回前端，他人主页不返回密码/手机号/tokenVersion等敏感字段
        UserProfileVo vo = new UserProfileVo();
        BeanUtils.copyProperties(user, vo);
        return vo;
    }
}
