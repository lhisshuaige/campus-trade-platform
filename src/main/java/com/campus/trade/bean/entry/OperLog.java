package com.campus.trade.bean.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志。它是 @Log + LogAspect 的落库载体，回答的是审计必须回答的四个问题：
 * 谁（user_id + username 快照）、在什么时候（create_time）、对哪个入口做了什么（action + method + params）、结果如何（status + error_msg + cost_ms）。
 * 与 notice/report 不同，这张表刻意不建外键：user_id 只是线索，username 是当时的快照，
 * 建了 FK + CASCADE 就等于"注销用户即销毁他的操作痕迹"（见 init.sql 注释）。
 */
@Data
@TableName("oper_log")
public class OperLog {

    // 结果：取值域必须和 init.sql 的 chk_oper_log_status 一致
    public static final int STATUS_FAIL = 0;
    public static final int STATUS_SUCCESS = 1;

    /** 各列长度上限，必须与 init.sql 的 oper_log 定义一致：超长会在 insert 时被截断，绝不能让一条脏日志把整次写入打回 */
    public static final int ACTION_MAX_LENGTH = 50;
    public static final int USERNAME_MAX_LENGTH = 50;
    public static final int REQUEST_URI_MAX_LENGTH = 200;
    public static final int HTTP_METHOD_MAX_LENGTH = 10;
    public static final int METHOD_MAX_LENGTH = 200;
    public static final int IP_MAX_LENGTH = 64;
    public static final int ERROR_MSG_MAX_LENGTH = 500;
    /** params 列是 TEXT，但入参里可能塞着一整篇商品描述，仍然要设一个上限 */
    public static final int PARAMS_MAX_LENGTH = 2000;

    @TableId(type = IdType.AUTO)
    private Long id;
    //操作人：登录/注册这类"还没有身份"的请求为 null
    private Long userId;
    private String username;
    //@Log 注解上写的那句话，给管理员看的，不是给程序看的
    private String action;
    private String requestUri;
    private String httpMethod;
    //类.方法：注解可以被复用（两处都写"删除商品"），method 才是不可歧义的那一列
    private String method;
    //入参摘要：敏感字段已打码、超长已截断，见 LogAspect
    private String params;
    private String ip;
    private Integer status;
    private String errorMsg;
    private Long costMs;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
