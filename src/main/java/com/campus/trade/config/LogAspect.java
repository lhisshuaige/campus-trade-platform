package com.campus.trade.config;

import cn.hutool.json.JSONUtil;
import com.campus.trade.bean.entry.OperLog;
import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.utils.Log;
import com.campus.trade.service.OperLogService;
import jakarta.annotation.Resource;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 操作日志切面：拦截标注了 @Log 的方法，记录 谁、在哪个入口、做了什么、结果如何、耗时多少。
 * 两路输出各有用途，不是重复实现：文件（log.info）给运维 grep，oper_log 表给管理员按条件查与统计。
 */
@Aspect
@Component
@Slf4j
public class LogAspect {

    @Resource
    private OperLogService operLogService;

    /**
     * 需要打码的入参字段名：口令类（password/pwd/token/secret/authorization）绝不落库明文；
     * 联系方式类（contact/phone/mobile）虽然对用户是公开信息，但审计日志的读者是管理员，
     * 没必要在业务表之外再存一份手机号。
     */
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(\"\\w*?(?:password|passwd|pwd|token|secret|authorization|contact|phone|mobile)\\w*?\"\\s*:\\s*)(?:\"[^\"]*\"|[^,}\\]]+)",
            Pattern.CASE_INSENSITIVE);
    //只替换冒号右边的值，键名和逗号都原样留着：替换后的 JSON 结构仍然合法，管理员能直接看懂
    private static final String MASKED = "$1\"***\"";

    @Around("@annotation(com.campus.trade.bean.utils.Log)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        long startTime = System.currentTimeMillis();

        String requestURI = "";
        String httpMethod = "";
        Long loginUserId = null;
        String loginUsername = null;
        String ip = null;
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            requestURI = request.getRequestURI();
            httpMethod = request.getMethod();
            ip = resolveIp(request);
            // 登录态由拦截器写入 request attribute：这里只读不算，绝不自己解析 token（口径只能有一份）
            loginUserId = (Long) request.getAttribute("loginUserId");
            loginUsername = (String) request.getAttribute("loginUsername");
        }

        MethodSignature signature = (MethodSignature) point.getSignature();
        Method targetMethod = signature.getMethod();
        Log logAnnotation = targetMethod.getAnnotation(Log.class);
        String operation = logAnnotation.value();

        String className = point.getTarget().getClass().getSimpleName();
        String methodName = point.getSignature().getName();
        String method = className + "." + methodName;
        //入参在业务执行前就取好：业务方法可能改动甚至置空传入的对象，事后再序列化会失真
        String params = summarizeParams(point.getArgs());

        try {
            Object result = point.proceed();
            long cost = System.currentTimeMillis() - startTime;
            log.info("[操作日志] {} {} 成功 | 操作: {} | 类: {} | 耗时: {}ms",
                    httpMethod, requestURI, operation, method, cost);
            writeDbLog(loginUserId, loginUsername, operation, requestURI, httpMethod, method,
                    params, ip, OperLog.STATUS_SUCCESS, null, cost);
            return result;
        } catch (Throwable e) {
            long cost = System.currentTimeMillis() - startTime;
            log.error("[操作日志] {} {} 失败 | 操作: {} | 类: {} | 耗时: {}ms | 异常: {}",
                    httpMethod, requestURI, operation, method, cost, e.getMessage());
            writeDbLog(loginUserId, loginUsername, operation, requestURI, httpMethod, method,
                    params, ip, OperLog.STATUS_FAIL, resolveErrorMsg(e), cost);
            throw e;
        }
    }

    /**
     * 落库。整段被 try/catch 罩住：记录日志是附属能力，
     * Redis 抖动、表还没建、字段超长，任何一种都只能让日志这一条丢失，不能把用户的正常请求一起打回。
     */
    private void writeDbLog(Long userId, String username, String action, String requestURI, String httpMethod,
                            String method, String params, String ip, int status, String errorMsg, long cost) {
        try {
            OperLog operLog = new OperLog();
            operLog.setUserId(userId);
            operLog.setUsername(username);
            operLog.setAction(action);
            operLog.setRequestUri(requestURI);
            operLog.setHttpMethod(httpMethod);
            operLog.setMethod(method);
            operLog.setParams(params);
            operLog.setIp(ip);
            operLog.setStatus(status);
            operLog.setErrorMsg(errorMsg);
            operLog.setCostMs(cost);
            //createTime 交给 Service 统一给值：切面只负责"发生了什么"，不负责"什么时候"
            operLogService.saveLog(operLog);
        } catch (Exception e) {
            log.warn("[操作日志] 落库失败，已忽略 | 操作: {} | 类: {} | 原因: {}", action, method, e.getMessage());
        }
    }

    //失败原因只留业务提示语：堆栈与 SQL 片段属于内部细节，即便落在只有管理员能查的表里也不该往外走
    private String resolveErrorMsg(Throwable e) {
        if (e instanceof BusinessException) {
            return e.getMessage();
        }
        return "系统异常(" + e.getClass().getSimpleName() + ")";
    }

    //来源IP：优先取代理写下的 X-Forwarded-For，其次 X-Real-IP，最后连接地址
    //注意 XFF 是请求头，没有可信反代时可以伪造，所以它只当排查线索，绝不用作鉴权依据
    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank() && !"unknown".equalsIgnoreCase(forwarded)) {
            //可能是"客户端, 一层代理, 二层代理"，第一个才是真实来源
            return forwarded.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank() && !"unknown".equalsIgnoreCase(real)) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }

    //入参摘要：滤掉不可序列化的容器对象，序列化后按字段名打码，最后交由 Service 截断
    private String summarizeParams(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        List<Object> printable = new ArrayList<>(args.length);
        for (Object arg : args) {
            if (arg instanceof ServletRequest || arg instanceof ServletResponse || arg instanceof MultipartFile) {
                continue;
            }
            printable.add(arg);
        }
        if (printable.isEmpty()) {
            return null;
        }
        String json;
        try {
            json = JSONUtil.toJsonStr(printable);
        } catch (Exception e) {
            //序列化不了就退化成"参数类型清单"：至少能看出这个方法被谁用什么调了，别让一条日志换一次告警
            json = "[无法序列化: " + printable.stream().map(a -> a.getClass().getSimpleName()).toList() + "]";
        }
        return SENSITIVE_FIELD.matcher(json).replaceAll(MASKED);
    }
}
