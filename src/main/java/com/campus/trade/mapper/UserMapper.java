package com.campus.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.trade.bean.entry.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

// 用户Mapper 用mybitsplus实现，简化代码实现增删改查
@Mapper
public interface UserMapper extends BaseMapper<User> {

    // 禁用并全端踢下线：status 与 token_version 必须同一条 SQL 原子改。
    // 只改 status 会出现"库里已禁用、存量 Token 还能用 24 小时"；
    // 版本自增放在 DB 层，不在 Java 里读出来 +1 再写回，避免与并发改密互相覆盖
    @Update("UPDATE `user` SET status = 0, token_version = token_version + 1 WHERE id = #{userId}")
    int disableAndKickOut(@Param("userId") Long userId);

    // 解禁：只改 status。不追加自增 —— 被禁期间那批 Token 早已在上一次禁用时作废，
    // 让"禁用→解禁"是一次不可逆的全端下线，安全侧只有更严没有放松
    @Update("UPDATE `user` SET status = 1 WHERE id = #{userId}")
    int enableUser(@Param("userId") Long userId);

    // 改密：新密码 + token_version 自增一条 SQL 搞定，语义与禁用一致（旧 Token 立即失效）
    @Update("UPDATE `user` SET password = #{password}, token_version = token_version + 1 WHERE id = #{userId}")
    int updatePasswordAndBumpVersion(@Param("userId") Long userId, @Param("password") String password);
}
