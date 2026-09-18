package com.campus.trade.bean.exception;

//统一错误码枚举：集中管理，避免散落的魔法数字
public enum ErrorCode {

    PARAM_ERROR(400, "参数错误"),
    BUSINESS_ERROR(400, "业务处理失败"),
    UNAUTHORIZED(401, "未登录或登录已失效"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后再试"),
    SERVER_ERROR(500, "服务器内部错误，请稍后重试");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }
}
