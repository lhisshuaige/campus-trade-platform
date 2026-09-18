package com.campus.trade.bean.exception;


import lombok.Data;

//这是一个处理异常业务服务的类 是一个自己抛出异常的类
@Data
public class BusinessException extends RuntimeException {
    private Integer code;
    private String msg;

    public BusinessException(ErrorCode errorCode) {
        // 把消息交给父类，保证 getMessage()/堆栈/日志能显示真实原因
        super(errorCode.getMsg());
        this.code = errorCode.getCode();
        this.msg = errorCode.getMsg();
    }

    public BusinessException(ErrorCode errorCode, String msg) {
        super(msg);
        this.code = errorCode.getCode();
        this.msg = msg;
    }

    @Deprecated
    public BusinessException(Integer code, String msg) {
        super(msg);
        this.code = code;
        this.msg = msg;
    }
}
