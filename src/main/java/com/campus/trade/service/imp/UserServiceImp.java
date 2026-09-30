package com.campus.trade.service.imp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.trade.bean.entry.Role;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
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

    // 编码器由容器给（P2-10），不再在这里自己 new：
    // “本站用什么算法、什么强度”只能有一份答案，写在 SecurityConfig 的 @Bean 里；
    // 字段类型取接口，将来换 Argon2 只改那一个 @Bean，本类一行不动
    @Resource
    private PasswordEncoder passwordEncoder;

    //每日登录 + 登出总量上限：防脚本滥用（与下面的「连续失败锁定」是两个维度，不要混为一谈）
    //上限做成配置，避免再出现「注释写 5 次、代码写 100 次」这种漂移
    @Value("${app.auth.login-logout-max-per-day:100}")
    private int maxLoginLogoutPerDay;

    //连续登录失败达到该次数即锁定账号
    @Value("${app.auth.login-fail-max:5}")
    private int loginFailMax;

    //锁定时长（分钟），同时是失败计数的 Redis TTL：到期自动解锁，不需要定时任务
    @Value("${app.auth.login-lock-minutes:15}")
    private int loginLockMinutes;

    @Resource
    private UserMapper userMapper;

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
        try {
            save(user);
        } catch (DuplicateKeyException e) {
            //"先查后插"两步之间可能被并发抢先，uk_username 才是唯一可靠的裁判：
            //预检给人话，唯一键给正确性（与 addCollect 同一写法）
            throw new BusinessException(ErrorCode.BUSINESS_ERROR,"用户名已存在,请更换");
        }
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
        //锁定判定必须在密码校验【之前】：
        //1) 旧写法把计数写在 matches 之后，密码错根本不计数，所谓上限对撞库完全无效
        //2) 锁定期内直接拒绝，省下每次约 100ms 的 BCrypt 比对，这本身就是抗爆破预算
        if (userActionLimitUtils.isLoginLocked(exsituser.getId(), loginFailMax)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "密码错误次数过多，账号已锁定，请 " + loginLockMinutes + " 分钟后再试");
        }
        if(!passwordEncoder.matches(userLoginDTO.getPassword(), exsituser.getPassword())){
            long fails = userActionLimitUtils.recordLoginFailure(exsituser.getId(), loginLockMinutes);
            //达到阈值才报锁定；未达阈值仍然只回同一句话，不告诉尝试者还剩几次额度（避免反向探测）
            if (fails >= loginFailMax) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                        "密码错误次数过多，账号已锁定，请 " + loginLockMinutes + " 分钟后再试");
            }
            throw new BusinessException(ErrorCode.PARAM_ERROR,"用户名或密码错误");
        }
        //密码对了才清除失败计数（错密码不能顺便把计数洗掉）
        userActionLimitUtils.clearLoginFailure(exsituser.getId());
        //判断是否被禁用（身份核验之后才能告知，否则未验密码就能探测“谁被禁了”）
        if(exsituser.getStatus() == 0){
            throw new BusinessException(ErrorCode.FORBIDDEN,"用户被禁用");
        }
        //每日登录/登出总量限制
        if(!userActionLimitUtils.tryAcquire(exsituser.getId(), maxLoginLogoutPerDay)){
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
        userActionLimitUtils.tryAcquire(jwtUtils.getUserId(token), maxLoginLogoutPerDay);
        tokenBlacklistUtils.add(token);
    }

    // 全端登出：不是“把这一个设备退掉”，而是“把这个账号发出去的全部 Token 收回来”
    @Override
    public void logoutAll(Long userId) {
        //复用登录/登出的当日额度。这条跟 logout 不是一个代价：它每次都要写库 + bump 版本，
        //不限流就能被脚本把 token_version 一路自增（而每一跳都把所有旧 Token 作废，
        //对着一个正在登录的会话反复调就是持续自我踢下线）
        if (!userActionLimitUtils.tryAcquire(userId, maxLoginLogoutPerDay)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "今日登录/登出次数已达上限,请明天再试");
        }
        //为什么不能用黑名单：黑名单只认服务器此刻见过的这一个 token，而手机/平板/网页各持有一份，
        //服务器根本枚举不出“这个用户名下现在一共飘着几个未过期 Token”。
        //token_version 是用户级的一把开关：改一次，此后所有旧版本一律比对不上
        userMapper.bumpTokenVersion(userId);
        //与改密/禁用同一条失效协议：只改库不删缓存，拦截器在 TTL（5 分钟）内仍拿到旧版本号、
        //比对通过，全端登出就形同没生效 —— P0-8 已经踩过的同一个坑
        tokenVersionUtils.evictAfterCommit(userId);
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
        //新密码 + token_version 自增，一条 SQL 原子完成（不在 Java 里读出来 +1 再写回）
        userMapper.updatePasswordAndBumpVersion(userId, passwordEncoder.encode(vo.getNewPassword()));
        //改密后 Token 版本 +1：此前签发的所有旧 Token 版本不再匹配，立即失效。
        //失效动作挂在提交之后 —— 提交前刷缓存会被并发读用旧值覆盖回去
        tokenVersionUtils.evictAfterCommit(userId);
    }

    //启用/禁用用户：禁用 = 改状态 + 全端踢下线，两件事必须绑死在 Service 里，
    //不能指望每个 Controller 都记得补一刀
    @Override
    public void changeUserStatus(Long userId, Integer status, Long operatorId) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "状态值只能为0或1");
        }
        if (userId == null || userId.equals(operatorId)) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "不能修改自己的状态");
        }
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        //把唯一的管理员禁掉 = 把自己锁在系统外，且 RBAC 演示直接崩
        if (status == 0 && Role.ADMIN.getCode().equals(user.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "不能禁用管理员账号");
        }
        if (status == 0) {
            userMapper.disableAndKickOut(userId);
        } else {
            userMapper.enableUser(userId);
        }
        //关键一步：版本缓存 TTL 5 分钟，不主动失效则拦截器仍会拿旧版本号比对通过 → 禁用形同没生效
        tokenVersionUtils.evictAfterCommit(userId);
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
