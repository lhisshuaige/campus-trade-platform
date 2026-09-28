package com.campus.trade.bean.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;


@TableName("user")
@Data
public class User {

    // ==== 入参校验口径：DTO 的注解值直接引用这里，不在两个文件里各抄一遍数字 ====
    // （与 Report.REASON_MAX_LENGTH、Goods.CONDITIONS 是同一手法：实体是口径的唯一来源）

    /** 用户名长度下限：单字符名在登录框里极易误触，也让「同形异名」更好玩 */
    public static final int USERNAME_MIN_LENGTH = 3;
    /** 用户名长度上限：比列宽 VARCHAR(50) 严 —— 列宽只是物理上限，这个业务口径要钉进 chk_user_username */
    public static final int USERNAME_MAX_LENGTH = 20;
    /**
     * 用户名允许的字符集：字母/数字/下划线/中文。
     * 为什么字符集只在 Java 侧、DB 侧只校长度：MySQL 的 REGEXP 不认 \u4e00 这类 Unicode 转义，
     * 硬把 Java 正则抄进 CHECK 只会得到一条跑不通的约束。两侧职责不同而非同一句话抄两遍：
     * Java 管「长什么样」并给人话提示，DB（chk_user_username）管「长度越界」以拦住手工 SQL。
     */
    public static final String USERNAME_REGEX = "^[0-9A-Za-z_\\u4e00-\\u9fa5]+$";

    public static final int PASSWORD_MIN_LENGTH = 6;
    public static final int PASSWORD_MAX_LENGTH = 20;
    /**
     * 密码只禁空白字符，不做「必须同时含大小写+数字」那套复杂度规则：
     * 在线爆破已经被 P0-9 的「错 5 次锁 15 分钟」挡住了，弱密码的真实威胁是库泄密后的离线撞库，
     * 那时救命的是 BCrypt 的 cost factor，不是规则里那个大写字母。
     * 而禁空白是能被真实故障验证的：复制粘贴带上首尾空格，用户就永久登不上。
     */
    public static final String PASSWORD_REGEX = "^\\S+$";

    /** 手机号：与 init.sql 的 chk_user_phone 同一条口径（DB 允许 NULL，@Pattern 对 null 也跳过，等价） */
    public static final String PHONE_REGEX = "^1[3-9]\\d{9}$";

    public static final int NICKNAME_MAX_LENGTH = 50;
    public static final int AVATAR_MAX_LENGTH = 500;

    // 角色取值域由 Role 枚举派生，不再手写一份字符串列表（三份口径必然漂移，见「统一口径靠删掉重复实现」）
    public static final List<String> ROLES = List.of(Role.USER.getCode(), Role.ADMIN.getCode());

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String nickname;
    private String phone;
    private String avatar;
    private String role;
    private Integer status;
    // Token 版本号：每次改密 +1，拦截器比对版本使旧 Token 立即失效
    private Integer tokenVersion;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
