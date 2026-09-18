package com.campus.trade.config;

import com.campus.trade.bean.utils.Log;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * 操作日志切面：拦截标注了 @Log 注解的方法，记录请求信息、操作内容、耗时与结果
 */
@Aspect
@Component
@Slf4j
public class LogAspect {

    @Around("@annotation(com.campus.trade.bean.utils.Log)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        long startTime = System.currentTimeMillis();

        String requestURI = "";
        String method = "";
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            requestURI = request.getRequestURI();
            method = request.getMethod();
        }

        MethodSignature signature = (MethodSignature) point.getSignature();
        Method targetMethod = signature.getMethod();
        Log logAnnotation = targetMethod.getAnnotation(Log.class);
        String operation = logAnnotation.value();

        String className = point.getTarget().getClass().getSimpleName();
        String methodName = point.getSignature().getName();

        try {
            Object result = point.proceed();
            long cost = System.currentTimeMillis() - startTime;
            log.info("[操作日志] {} {} 成功 | 操作: {} | 类: {}.{} | 耗时: {}ms",
                    method, requestURI, operation, className, methodName, cost);
            return result;
        } catch (Throwable e) {
            long cost = System.currentTimeMillis() - startTime;
            log.error("[操作日志] {} {} 失败 | 操作: {} | 类: {}.{} | 耗时: {}ms | 异常: {}",
                    method, requestURI, operation, className, methodName, cost, e.getMessage());
            throw e;
        }
    }
}
