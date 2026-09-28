package com.campus.trade.bean.DTO.result;

import com.campus.trade.bean.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

// 统一返回结果类
@Schema(description = "统一返回结果类")
@Data
public class MyResult <T> {

    @Schema(description = "状态码,200表示成功")
    private Integer code;
    @Schema(description = "返回信息")
    private String msg;
    @Schema(description = "返回数据")
    private T data;

    //返回成功结果（码与文案统一走 ErrorCode，不在这里写魔法数字）
    public static <T> MyResult<T> success(T data){
        MyResult<T> result = new MyResult<>();
        result.setCode(ErrorCode.SUCCESS.getCode());
        result.setMsg(ErrorCode.SUCCESS.getMsg());
        result.setData(data);
        return result;
    }
    public static <T> MyResult<T> success(){
        MyResult<T> result = new MyResult<>();
        result.setCode(ErrorCode.SUCCESS.getCode());
        result.setMsg(ErrorCode.SUCCESS.getMsg());
        return result;
    }


    //返回失败结果（无参版本=未预期的服务端错误，与全局兜底同一口径）
    public static <T> MyResult<T> error(){
        MyResult<T> result = new MyResult<>();
        result.setCode(ErrorCode.SERVER_ERROR.getCode());
        result.setMsg(ErrorCode.SERVER_ERROR.getMsg());
        return result;
    }
    public static <T> MyResult<T> error(Integer code, String msg){
        MyResult<T> result = new MyResult<>();
        result.setCode(code);
        result.setMsg(msg);
        return result;
    }
    public static <T> MyResult<T> error(Integer code, String msg, T data){
        MyResult<T> result = new MyResult<>();
        result.setCode(code);
        result.setMsg(msg);
        result.setData(data);
        return result;
    }
}
