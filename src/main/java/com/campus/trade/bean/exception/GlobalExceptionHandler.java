package com.campus.trade.bean.exception;


import com.campus.trade.bean.DTO.result.MyResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.LinkedHashMap;
import java.util.Map;

//全局异常处理类
//所有失败分支都返回「真实的 HTTP 状态码 + 统一 body」：
//原来一律 HTTP 200、只靠 body.code 区分，导致网关/监控/前端拦截器把 5xx 当成功，告警永久漏报
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    //捕获业务异常(BusinessException 这个自己抛出异常)
    //BusinessException 的 code 就是 ErrorCode 的 HTTP 取值，拦截器里抛的 401/403 也走这里，状态码自然对得上
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<MyResult<?>> handleBusinessException(BusinessException e){
        return toResponse(e.getCode(), e.getMsg());
    }
    //捕获数据校验异常
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<MyResult<?>> handleDataException(MethodArgumentNotValidException e){
        return handleValidationError(e.getBindingResult());
    }

    //表单/查询参数绑定校验失败：GET 接口上 `@Valid XxxQueryDTO`（不带 @RequestBody）失败抛的是
    //BindException 而不是 MethodArgumentNotValidException，原来没有对应 handler，
    //于是一个 ?pageSize=1000 就能拿到 500 —— 把“你自己传错了”报成“服务端坏了”，正是 P2-3 要避免的那类错
    @ExceptionHandler(BindException.class)
    public ResponseEntity<MyResult<?>> handleBindException(BindException e) {
        return handleValidationError(e.getBindingResult());
    }

    /**
     * 两个校验异常共用一份「字段 → 提示」的收集逻辑。
     * <p>
     * 参数类型取接口 {@link BindingResult} 而不是某个具体异常：两种异常都能从它拿到 BindingResult，
     * 于是这里不依赖「MethodArgumentNotValidException 是否继承 BindException」这种跨版本会变的细节。
     * 若继承关系成立，Spring 按最具体类型匹配，上面的 @RequestBody 分支仍走原 handler，行为不变。
     * <p>
     * 用 LinkedHashMap 而不是 HashMap：多个字段同时不合法时返回顺序 = 声明顺序，
     * 前端取第一条提示就是稳定那一条（HashMap 小容量下看着有序是实现细节，不是承诺）。
     * 只记字段名不记字段值：入参里可能是密码。
     */
    private ResponseEntity<MyResult<?>> handleValidationError(BindingResult bindingResult) {
        Map<String, String> errorMap = new LinkedHashMap<>();
        for (FieldError fieldError : bindingResult.getFieldErrors()) {
            //获取属性名 + 错误信息
            errorMap.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        log.warn("参数校验失败 fields={}", errorMap.keySet());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(MyResult.error(ErrorCode.PARAM_ERROR.getCode(), ErrorCode.PARAM_ERROR.getMsg(), errorMap));
    }


    //请求参数类型不匹配（如 ?id=abc 绑到 Long）：这是客户端传错了，应该是 400 而不是 500。
    //底层 message 带具体类型与值，只进日志不进响应
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<MyResult<?>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配 param={}", e.getName(), e);
        return toResponse(ErrorCode.PARAM_ERROR.getCode(), "参数格式不正确");
    }

    //必填请求参数未传（@RequestParam 默认 required=true）
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<MyResult<?>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必填参数 param={}", e.getParameterName(), e);
        return toResponse(ErrorCode.PARAM_ERROR.getCode(), "缺少必填参数");
    }

    //请求体不可读：JSON 格式错、字段类型不匹配、body 为空
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<MyResult<?>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败", e);
        return toResponse(ErrorCode.PARAM_ERROR.getCode(), "请求体格式不正确");
    }

    //方法不匹配：@GetMapping 的路径用 POST 调。不接住就落到兜底变 500，
    //而 500 会让调用方以为服务端坏了，实际上是自己请求发歪了
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<MyResult<?>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方式不支持 method={}", e.getMethod());
        return toResponse(ErrorCode.METHOD_NOT_ALLOWED.getCode(), ErrorCode.METHOD_NOT_ALLOWED.getMsg());
    }

    //Content-Type 不对：@RequestBody 只吃 application/json，忘带头部时 Spring 报的是 415 而不是 400。
    //单独接住是因为手工测试（curl/Postman）极易触发，给一句能照着改的提示
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<MyResult<?>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("请求内容类型不支持 contentType={}", e.getContentType());
        return toResponse(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getCode(), ErrorCode.UNSUPPORTED_MEDIA_TYPE.getMsg());
    }

    //上传超过 spring.servlet.multipart 限制：落到兜底就是一个误导人的 500
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<MyResult<?>> handleTooLarge(MaxUploadSizeExceededException e) {
        log.warn("上传内容超限", e);
        return toResponse(ErrorCode.PAYLOAD_TOO_LARGE.getCode(), ErrorCode.PAYLOAD_TOO_LARGE.getMsg());
    }

    //唯一键冲突：Service 层已逐个 catch（addCollect/register/category），这里兜住未来漏网的那一处。
    //DuplicateKeyException 是 DataIntegrityViolationException 的子类，
    //Spring 按「最具体类型」匹配，所以不会被下面那个通用 handler 抢走
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<MyResult<?>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一键冲突", e);
        return toResponse(ErrorCode.BUSINESS_ERROR.getCode(), "数据已存在，请勿重复提交");
    }

    //中间件连接失败的兜底翻译：utils 层已全量包装，这里兜住未来某处漏网的裸 Redis 调用，
    //避免底层异常落到兜底 500 并把连接串之类的细节写进日志之外的响应
    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<MyResult<?>> handleMiddlewareDown(RedisConnectionFailureException e) {
        log.error("Redis 连接失败", e);
        return toResponse(ErrorCode.SERVICE_UNAVAILABLE.getCode(), ErrorCode.SERVICE_UNAVAILABLE.getMsg());
    }

    //数据完整性约束兜底翻译：FK RESTRICT / CHECK / UNIQUE 冲突。
    //Service 层预检负责常规路径，这里兜住"预检与写入之间的竞态"以及未来漏网的约束冲突，
    //避免落到全局 500；只记日志不外泄 MySQL 报错细节（含表名/约束名）
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<MyResult<?>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.error("数据完整性约束冲突", e);
        return toResponse(ErrorCode.BUSINESS_ERROR.getCode(),
                "该数据仍被其他记录引用或不满足约束，操作被拒绝");
    }

    //全局异常处理(最后保底)：记录完整堆栈，对外统一返回 500，不暴露内部细节
    @ExceptionHandler(Exception.class)
    public ResponseEntity<MyResult<?>> error(Exception e){
        log.error("系统异常", e);
        return toResponse(ErrorCode.SERVER_ERROR.getCode(), ErrorCode.SERVER_ERROR.getMsg());
    }

    /**
     * 业务码 → 真实 HTTP 状态码。ErrorCode 的取值本身就是 HTTP 语义（400/401/403/404/405/413/415/429/503），
     * 这里直接复用它定状态，body 里的 code/msg 一字不改地保留 —— 老前端按 code 分支的逻辑不用动，
     * 新前端/网关/监控则可以只看 HTTP 状态就判成败。
     * <p>
     * resolve 返回 null 说明有人塞了个非 HTTP 取值：升级成 500，宁可报重也不报轻，
     * 绝不能把未知错误伪装成 2xx。注意 401/403 走这里之后仍带 JSON body，
     * 与 Spring Security 默认的空 body 401 不同，前端能直接拿 msg 提示用户
     */
    private ResponseEntity<MyResult<?>> toResponse(Integer code, String msg) {
        HttpStatus status = HttpStatus.resolve(code == null ? -1 : code);
        if (status == null) {
            log.warn("业务码不是合法的 HTTP 状态码，按 500 处理 code={}", code);
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity.status(status).body(MyResult.error(code, msg));
    }
}
