package com.campus.trade.service.impl;

import com.campus.trade.bean.dto.request.user.UserChangePasswordDTO;
import com.campus.trade.bean.dto.request.user.UserLoginDTO;
import com.campus.trade.bean.dto.request.user.UserRegisterDTO;
import com.campus.trade.bean.entity.User;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.utils.JwtUtils;
import com.campus.trade.bean.utils.TokenBlacklistUtils;
import com.campus.trade.bean.utils.TokenVersionUtils;
import com.campus.trade.bean.utils.UserActionLimitUtils;
import com.campus.trade.bean.vo.UserVo;
import com.campus.trade.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2：不启 Spring 容器、不连 MySQL/Redis，只验证 Service 的**决策逻辑与调用顺序**。
 *
 * <p>为什么值得单独一层：这些断言几乎全是「顺序」和「谁没被调用」，
 * 而这两类恰是重构时最容易无声改坏的东西 —— 编译通过、接口返回码也一样，
 * 但安全性已经变了。集成测试（L3）能验出「结果对不对」，验不出「有没有提前泄露」。
 *
 * <p>实现手法说明（以及为什么不用 @InjectMocks）：
 * {@code UserServiceImpl} 继承 MyBatis-Plus 的 {@code ServiceImpl}，
 * 它的 {@code getOne/getById/save/updateById} 是 <b>IService 上的 default 方法</b>
 * （3.5.16 实测：ServiceImpl 类里根本没有这几个方法声明，全部继承自 CrudService/IService），
 * {@code @InjectMocks} 只会注入 {@code @Resource} 字段，管不到这条继承链。
 * 所以这里用 {@code Mockito.spy(真实对象)} + {@link ReflectionTestUtils} 手工灌字段，
 * 并把基类方法直接打桩 —— 顺带的好处是：测试不依赖 MyBatis-Plus 内部到底调了
 * {@code selectOne(wrapper)} 还是 {@code selectOne(wrapper, boolean)}，
 * 那两个重载在 3.4/3.5 之间就换过签名，绑上去等于给测试装了个「升级即失效」的定时器。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("L2 UserServiceImpl：登录/改密/禁用的安全顺序")
class UserServiceImplSecurityTest {

    @Mock
    private JwtUtils jwtUtils;
    @Mock
    private TokenBlacklistUtils tokenBlacklistUtils;
    @Mock
    private TokenVersionUtils tokenVersionUtils;
    @Mock
    private UserActionLimitUtils userActionLimitUtils;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private UserMapper userMapper;

    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new UserServiceImpl());
        ReflectionTestUtils.setField(service, "jwtUtils", jwtUtils);
        ReflectionTestUtils.setField(service, "tokenBlacklistUtils", tokenBlacklistUtils);
        ReflectionTestUtils.setField(service, "tokenVersionUtils", tokenVersionUtils);
        ReflectionTestUtils.setField(service, "userActionLimitUtils", userActionLimitUtils);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        // 这一行是真实踩出来的：MyBatis-Plus 3.5.16 里 ServiceImpl **自己一个方法也没声明**
        // （javap 实测只有一个构造器），save/getById/getOne 全是 IService 的 default 方法，
        // 它们最终走 getBaseMapper() —— 而 spy 对「没有打桩的方法」是真跑的。
        // 所以只要断言里出现 verify(service).save(...)，baseMapper 就必须是可用的，
        // 否则 MP 直接抛 baseMapper can not be null
        ReflectionTestUtils.setField(service, "baseMapper", userMapper);
        // 这三个值本来是 @Value 注入的，不起容器就得手工给；给的就是配置里的缺省值，
        // 于是「缺省值改了会不会连带改行为」也被固定在这里
        ReflectionTestUtils.setField(service, "maxLoginLogoutPerDay", 100);
        ReflectionTestUtils.setField(service, "loginFailMax", 5);
        ReflectionTestUtils.setField(service, "loginLockMinutes", 15);
    }

    private User user(Long id, String username, String role, Integer status) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setPassword("$2a$10$storedhash");
        u.setRole(role);
        u.setStatus(status);
        u.setTokenVersion(3);
        return u;
    }

    private UserLoginDTO loginDto(String username, String password) {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }

    // ===================== 登录：判定顺序就是安全边界 =====================

    @Test
    @DisplayName("锁定判定必须发生在 BCrypt 比对之前")
    void lockCheckRunsBeforePasswordMatch() {
        User u = user(7L, "zhang_san", "user", 1);
        doReturn(u).when(service).getOne(any());
        when(userActionLimitUtils.isLoginLocked(7L, 5)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.login(loginDto("zhang_san", "whatever")));

        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());
        // 锁定期内一次 BCrypt 都不许做：每次约 100ms 的比对本身就是抗爆破预算
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        InOrder order = inOrder(userActionLimitUtils);
        order.verify(userActionLimitUtils).isLoginLocked(7L, 5);
    }

    @Test
    @DisplayName("用户不存在与密码错误必须给同一句话，否则登录框就是个用户名枚举接口")
    void unknownUserAndWrongPasswordAreIndistinguishable() {
        doReturn(null).when(service).getOne(any());
        BusinessException notFound = assertThrows(BusinessException.class,
                () -> service.login(loginDto("nobody", "abc123")));

        User u = user(8L, "zhang_san", "user", 1);
        doReturn(u).when(service).getOne(any());
        when(passwordEncoder.matches("wrongpwd", "$2a$10$storedhash")).thenReturn(false);
        when(userActionLimitUtils.recordLoginFailure(8L, 15)).thenReturn(1L);
        BusinessException wrongPwd = assertThrows(BusinessException.class,
                () -> service.login(loginDto("zhang_san", "wrongpwd")));

        assertEquals(notFound.getMsg(), wrongPwd.getMsg());
        assertEquals(notFound.getCode(), wrongPwd.getCode());
        assertTrue(notFound.getMsg().contains("用户名或密码错误"));
    }

    @Test
    @DisplayName("没到阈值的失败不能告诉尝试者还剩几次")
    void failureResponseDoesNotLeakRemainingAttempts() {
        User u = user(9L, "zhang_san", "user", 1);
        doReturn(u).when(service).getOne(any());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(userActionLimitUtils.recordLoginFailure(9L, 15)).thenReturn(2L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.login(loginDto("zhang_san", "bad")));
        assertTrue(ex.getMsg().indexOf('2') < 0, "响应里出现了数字，等于把剩余次数告诉了对撞库的人");
    }

    @Test
    @DisplayName("达到阈值那一次立刻改口成锁定")
    void thresholdHitUpgradesToLockout() {
        User u = user(10L, "zhang_san", "user", 1);
        doReturn(u).when(service).getOne(any());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(userActionLimitUtils.recordLoginFailure(10L, 15)).thenReturn(5L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.login(loginDto("zhang_san", "bad")));

        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("15"));
    }

    @Test
    @DisplayName("禁用状态只能在密码验对之后被得知")
    void disabledIsRevealedOnlyAfterCorrectPassword() {
        User banned = user(11L, "zhang_san", "user", 0);
        doReturn(banned).when(service).getOne(any());
        when(passwordEncoder.matches("bad", "$2a$10$storedhash")).thenReturn(false);
        when(userActionLimitUtils.recordLoginFailure(11L, 15)).thenReturn(1L);

        BusinessException beforeAuth = assertThrows(BusinessException.class,
                () -> service.login(loginDto("zhang_san", "bad")));
        assertNotEquals(ErrorCode.FORBIDDEN.getCode(), beforeAuth.getCode());

        when(passwordEncoder.matches("right", "$2a$10$storedhash")).thenReturn(true);
        BusinessException afterAuth = assertThrows(BusinessException.class,
                () -> service.login(loginDto("zhang_san", "right")));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), afterAuth.getCode());
    }

    @Test
    @DisplayName("密码错的时候不许顺手清掉失败计数")
    void failureKeepsTheCounter() {
        User u = user(12L, "zhang_san", "user", 1);
        doReturn(u).when(service).getOne(any());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(userActionLimitUtils.recordLoginFailure(12L, 15)).thenReturn(3L);

        assertThrows(BusinessException.class, () -> service.login(loginDto("zhang_san", "bad")));

        verify(userActionLimitUtils, never()).clearLoginFailure(anyLong());
    }

    @Test
    @DisplayName("登录成功要清计数、要带用户级版本号签发 Token")
    void successClearsCounterAndSignsVersionedToken() {
        User u = user(13L, "zhang_san", "admin", 1);
        doReturn(u).when(service).getOne(any());
        when(passwordEncoder.matches("right", "$2a$10$storedhash")).thenReturn(true);
        when(userActionLimitUtils.tryAcquire(13L, 100)).thenReturn(true);
        when(jwtUtils.generateToken(13L, "zhang_san", "admin", 3)).thenReturn("jwt.token.value");

        assertEquals("jwt.token.value", service.login(loginDto("zhang_san", "right")));

        verify(userActionLimitUtils).clearLoginFailure(13L);
        verify(jwtUtils).generateToken(13L, "zhang_san", "admin", 3);
    }

    @Test
    @DisplayName("当日额度用完就拒登录，且不再签发 Token")
    void dailyQuotaBlocksLogin() {
        User u = user(14L, "zhang_san", "user", 1);
        doReturn(u).when(service).getOne(any());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(userActionLimitUtils.tryAcquire(14L, 100)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.login(loginDto("zhang_san", "right")));

        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());
        verify(jwtUtils, never()).generateToken(anyLong(), anyString(), anyString(), any());
    }

    // ===================== 注册：明文密码必须止步于 DTO =====================

    @Test
    @DisplayName("入库的是编码后的密码，且状态/角色由服务端定，不接受前端传值")
    void registerPersistsEncodedPasswordAndServerSideDefaults() {
        doReturn(null).when(service).getOne(any());
        when(passwordEncoder.encode("abc123")).thenReturn("$2a$10$freshhash");
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setUsername("zhang_san");
        dto.setPassword("abc123");
        dto.setNickname("张三丰");

        service.register(dto);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(service).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("$2a$10$freshhash", saved.getPassword());
        assertNotEquals("abc123", saved.getPassword());
        assertEquals(1, saved.getStatus());
        assertEquals("user", saved.getRole());
        assertEquals("张三丰", saved.getNickname());
    }

    @Test
    @DisplayName("预检拦下重复名时不该走到 insert")
    void duplicateUsernameIsCaughtBeforeInsert() {
        doReturn(user(1L, "zhang_san", "user", 1)).when(service).getOne(any());
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setUsername("zhang_san");
        dto.setPassword("abc123");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.register(dto));

        assertTrue(ex.getMsg().contains("用户名已存在"));
        verify(service, never()).save(any());
    }

    @Test
    @DisplayName("预检和插入之间的并发窗口由 uk_username 兜住，并翻译成人话")
    void uniqueKeyRaceIsTranslatedIntoBusinessException() {
        doReturn(null).when(service).getOne(any());
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$freshhash");
        doThrow(new DuplicateKeyException("Duplicate entry for key 'uk_username'"))
                .when(service).save(any());
        UserRegisterDTO dto = new UserRegisterDTO();
        dto.setUsername("zhang_san");
        dto.setPassword("abc123");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.register(dto));

        assertEquals(ErrorCode.BUSINESS_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMsg().contains("请更换"));
    }

    // ===================== 改密 / 全端登出 / 禁用 =====================

    @Test
    @DisplayName("改密只走那条原子 SQL，且送进库的必须是新密码的编码值")
    void changePasswordUsesAtomicBumpAndNeverStoresPlaintext() {
        User u = user(21L, "zhang_san", "user", 1);
        doReturn(u).when(service).getById(21L);
        when(passwordEncoder.matches("abc123", "$2a$10$storedhash")).thenReturn(true);
        when(passwordEncoder.encode("newpass6")).thenReturn("$2a$10$newhash");
        UserChangePasswordDTO dto = new UserChangePasswordDTO();
        dto.setOldPassword("abc123");
        dto.setNewPassword("newpass6");

        service.changePassword(21L, dto);

        verify(userMapper).updatePasswordAndBumpVersion(21L, "$2a$10$newhash");
        // 写 any(User.class) 而不是 any()：3.5.16 的 BaseMapper 同时有 updateById(T)
        // 和 updateById(Collection<T>) 两个重载，光靠 any() javac 会直接报“引用不明确”
        verify(userMapper, never()).updateById(any(User.class));
        verify(tokenVersionUtils).evictAfterCommit(21L);
    }

    @Test
    @DisplayName("旧密码不对时一个字节都不该写库")
    void wrongOldPasswordWritesNothing() {
        User u = user(22L, "zhang_san", "user", 1);
        doReturn(u).when(service).getById(22L);
        when(passwordEncoder.matches("nope", "$2a$10$storedhash")).thenReturn(false);
        UserChangePasswordDTO dto = new UserChangePasswordDTO();
        dto.setOldPassword("nope");
        dto.setNewPassword("newpass6");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changePassword(22L, dto));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(userMapper, never()).updatePasswordAndBumpVersion(anyLong(), anyString());
        verify(tokenVersionUtils, never()).evictAfterCommit(anyLong());
    }

    @Test
    @DisplayName("登出本身永远放行，但当前 token 必须进黑名单")
    void logoutAlwaysSucceedsAndBlacklists() {
        when(jwtUtils.getUserId("jwt.token.value")).thenReturn(31L);
        when(userActionLimitUtils.tryAcquire(31L, 100)).thenReturn(false);

        service.logout("jwt.token.value");

        verify(tokenBlacklistUtils).add("jwt.token.value");
    }

    @Test
    @DisplayName("全端登出被限流时不能顺手把 token_version 抬一格")
    void throttledLogoutAllDoesNotBumpVersion() {
        when(userActionLimitUtils.tryAcquire(32L, 100)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.logoutAll(32L));

        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());
        verify(userMapper, never()).bumpTokenVersion(anyLong());
        verify(tokenVersionUtils, never()).evictAfterCommit(anyLong());
    }

    @Test
    @DisplayName("全端登出靠版本号而不是黑名单 —— 服务器枚举不出用户手上飘着几个 Token")
    void logoutAllBumpsVersionInsteadOfBlacklisting() {
        when(userActionLimitUtils.tryAcquire(33L, 100)).thenReturn(true);

        service.logoutAll(33L);

        verify(userMapper, times(1)).bumpTokenVersion(33L);
        verify(tokenVersionUtils).evictAfterCommit(33L);
        verify(tokenBlacklistUtils, never()).add(anyString());
    }

    @Test
    @DisplayName("状态值只认 0 和 1，其它一律先拦下")
    void illegalStatusRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changeUserStatus(41L, 2, 99L));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(service, never()).getById(any());
    }

    @Test
    @DisplayName("管理员不能改自己的状态，而且这一步不查库")
    void selfStatusChangeRejectedWithoutQuery() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changeUserStatus(42L, 0, 42L));

        assertEquals(ErrorCode.BUSINESS_ERROR.getCode(), ex.getCode());
        verify(service, never()).getById(any());
    }

    @Test
    @DisplayName("禁用唯一管理员等于把自己锁在系统外")
    void disablingAdminIsForbidden() {
        User admin = user(43L, "admin_root", "admin", 1);
        doReturn(admin).when(service).getById(43L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.changeUserStatus(43L, 0, 99L));

        assertEquals(ErrorCode.FORBIDDEN.getCode(), ex.getCode());
        verify(userMapper, never()).disableAndKickOut(anyLong());
    }

    @Test
    @DisplayName("禁用 = 改状态 + 踢下线 + 清版本缓存，三件事绑死在 Service 里")
    void disableKicksOutAndEvictsCache() {
        User u = user(44L, "zhang_san", "user", 1);
        doReturn(u).when(service).getById(44L);

        service.changeUserStatus(44L, 0, 99L);

        verify(userMapper).disableAndKickOut(44L);
        verify(userMapper, never()).enableUser(anyLong());
        verify(tokenVersionUtils).evictAfterCommit(44L);
    }

    @Test
    @DisplayName("解禁不抬版本号 —— 禁用期间那批 Token 早已作废，安全侧只更严不放松")
    void enableDoesNotBumpVersion() {
        User u = user(45L, "zhang_san", "user", 0);
        doReturn(u).when(service).getById(45L);

        service.changeUserStatus(45L, 1, 99L);

        verify(userMapper).enableUser(45L);
        verify(userMapper, never()).disableAndKickOut(anyLong());
        verify(tokenVersionUtils).evictAfterCommit(45L);
    }

    // ===================== 出参不泄露敏感字段 =====================

    @Test
    @DisplayName("查询用户信息返回的是 VO，密码与 tokenVersion 不在里面")
    void userInfoDoesNotCarrySecrets() {
        User u = user(51L, "zhang_san", "user", 1);
        doReturn(u).when(service).getById(51L);

        UserVo vo = service.getUserInfo(51L);

        assertEquals("zhang_san", vo.getUsername());
        // 用反射扫字段而不是硬编码“没有 password”：UserVo 少一个字段是编译期就能发现的事，
        // 多一个 password / tokenVersion 字段才是这里的真实风险（将来有人为方便直接 copyProperties 到实体）
        for (java.lang.reflect.Field f : vo.getClass().getDeclaredFields()) {
            String n = f.getName().toLowerCase();
            assertTrue(!n.contains("password") && !n.contains("tokenversion"),
                    "UserVo 多了敏感字段：" + f.getName());
        }
    }
}
