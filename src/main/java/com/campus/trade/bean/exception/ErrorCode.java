package com.campus.trade.bean.exception;

//统一错误码枚举：集中管理，避免散落的魔法数字
public enum ErrorCode {

    // 成功码也收口进枚举，避免 MyResult 里再写魔法数字
    SUCCESS(200, "success"),
    PARAM_ERROR(400, "参数错误"),
    BUSINESS_ERROR(400, "业务处理失败"),
    UNAUTHORIZED(401, "未登录或登录已失效"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    // 路径存在但方法不对（把 GET 接口用 POST 调）：curl/Postman 手工验证时最高频的错误
    METHOD_NOT_ALLOWED(405, "请求方式不正确"),
    // Content-Type 缺失或不是 application/json：@RequestBody 解析前的拦截，与"请求体格式错(400)"区分开
    UNSUPPORTED_MEDIA_TYPE(415, "请求内容类型不支持，请使用 application/json"),
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后再试"),
    // 上传体超过 spring.servlet.multipart 限制：属于"请求太大"而非"服务器坏了"，给 413 让前端能提示用户
    PAYLOAD_TOO_LARGE(413, "上传内容过大，请压缩后重试"),
    // Redis 等中间件不可用：与"服务器内部错误(未预期 bug)"区分开，
    // 语义是"依赖的组件暂时不干活"，客户端可原样重试
    SERVICE_UNAVAILABLE(503, "系统繁忙，请稍后重试"),
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
