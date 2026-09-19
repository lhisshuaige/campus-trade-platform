package com.campus.trade.bean.exception;


import com.campus.trade.bean.DTO.result.MyResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;

//全局异常处理类
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    //捕获业务异常(BusinessException 这个自己抛出异常)
    @ExceptionHandler(BusinessException.class)
    public MyResult handleBusinessException(BusinessException e){
        Integer code = e.getCode();
        String msg = e.getMsg();
        return MyResult.error(code,msg);
    }
    //捕获数据校验异常
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public MyResult handleDataException(MethodArgumentNotValidException e){
        Map<String, String> errorMap = new HashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            //获取属性名
            String field = fieldError.getField();
            //获取错误信息
            String message = fieldError.getDefaultMessage();
            errorMap.put(field,message);
        }
        return MyResult.error(ErrorCode.PARAM_ERROR.getCode(), ErrorCode.PARAM_ERROR.getMsg(), errorMap);
    }


    //全局异常处理(最后保底)：记录完整堆栈，对外统一返回 500，不暴露内部细节
    @ExceptionHandler(Exception.class)
    public MyResult error(Exception e){
        log.error("系统异常", e);
        return MyResult.error(ErrorCode.SERVER_ERROR.getCode(), ErrorCode.SERVER_ERROR.getMsg());
    }
}
