package com.campus.trade.bean.vo.result;

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

    //返回成功结果
    public static <T> MyResult<T> success(T data){
        MyResult<T> result = new MyResult<>();
        result.setCode(200);
        result.setMsg("success");
        result.setData(data);
        return result;
    }
    public static <T> MyResult<T> success(){
        MyResult<T> result = new MyResult<>();
        result.setCode(200);
        result.setMsg("success");
        return result;
    }


    //返回失败结果
    public static <T> MyResult<T> error(){
        MyResult<T> result = new MyResult<>();
        result.setCode(500);
        result.setMsg("error");
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
