package com.campus.trade.controller;

import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.DTO.result.MyResult;
import com.campus.trade.bean.utils.ImageContentUtils;
import com.campus.trade.bean.utils.Log;
import com.campus.trade.bean.utils.RateLimit;
import com.campus.trade.bean.utils.RedisContent;
import com.campus.trade.bean.utils.UserActionLimitUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/file")
@Tag(name = "文件上传接口")
public class FileController {

    @Value("${upload.path}")
    private String uploadPath;

    // 图片对外基地址（反代/对象存储的域名）。留空 = 回退到从请求推断，只适合本机直连
    @Value("${upload.base-url:}")
    private String baseUrl;

    // 单用户每日上传张数上限。防的是“脚本刷盘”：本地磁盘是当前架构里唯一没有备份的组件，
    // 也是上不了水平扩展的那一个（集群要换 OSS/MinIO，那一步仍在 P2 之外）
    @Value("${upload.max-per-user-per-day:30}")
    private int maxUploadPerDay;

    @Resource
    private ImageContentUtils imageContentUtils;

    @Resource
    private UserActionLimitUtils userActionLimitUtils;

    @PostMapping("/upload")
    @Operation(summary = "上传图片",
            description = "仅接受图片格式，且会校验文件头与后缀一致；单用户每日上限见 upload.max-per-user-per-day")
    // 上传是写操作且会落盘，属于“事后要能回答谁什么时候传了什么”的那一类（参数里的 MultipartFile 已被切面滤掉）
    @Log("上传图片")
    //与下面的「每日张数」是两个维度，不能合并：那一个管【总量】（一天 30 张，防刷满磁盘），
    //这一个管【频率】（一分钟 10 张，防脚本连发）。
    //两层对“格式非法的请求要不要计数”的答案是相反的，而且都对：
    //日额度是一天的命运，不能被伪装文件的格式错误消耗；分钟闸门拦的就是“尝试”本身
    @RateLimit(maxCount = 10, message = "上传图片过于频繁，请稍后再试")
    public MyResult<String> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        Long loginUserId = (Long) request.getAttribute("loginUserId");
        // 不该发生：/file/upload 一直在 JwtInterceptor 的保护范围内（没进任何 exclude 列表）。
        // 真拿到 null 说明这个路径被误加进了白名单 —— 必须拒绝，否则所有游离请求会共用 "null" 这一个额度桶，
        // 限额形同不存在，事后审计也查不到人
        if (loginUserId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择文件");
        }

        // 顺序有讲究：先看后缀（最便宜，且能给出最具体的提示）→ 再看内容（一次字节比较 + 一次解码）
        // → 再计额度 → 最后才落盘：明显非法的请求在写盘前就返回，磁盘与 Redis 都不为它付成本
        String suffix = resolveSuffix(file.getOriginalFilename());
        imageContentUtils.assertRealImage(suffix, file);

        // 限流放在内容校验之后：格式不对根本不消耗额度
        //（否则拿一批伪装成 jpg 的文本文件就能把某用户的当日额度刷光）
        if (!userActionLimitUtils.tryAcquire(RedisContent.User_Upload_Count_KEY, loginUserId, maxUploadPerDay)) {
            log.info("上传触顶 userId={} limit={}", loginUserId, maxUploadPerDay);
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "今日上传图片已达上限(" + maxUploadPerDay + " 张)，请明天再试");
        }

        // 落盘名不采用客户端给的任何部分：UUID + 由内容推出来的后缀。
        // 于是 ../../x、超长文件名、名字里带换行这些全部进不了文件系统这一层（也不会有“覆盖别人文件”的碰撞）
        String newFilename = UUID.randomUUID().toString().replace("-", "") + suffix;

        File dir = new File(uploadPath);
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("创建上传目录失败 path={}", uploadPath);
            throw new BusinessException(ErrorCode.SERVER_ERROR, "图片保存失败，请稍后重试");
        }

        try {
            file.transferTo(new File(dir.getAbsolutePath() + File.separator + newFilename));
        } catch (IOException | IllegalStateException e) {
            // 原来是 "文件上传失败: " + e.getMessage()：把底层异常信息拼进响应，违反项目自己的
            // “异常不外泄”规范（磁盘路径、权限细节会跟着漏出去）。堆栈只进日志
            log.error("图片落盘失败 userId={} filename={} size={}", loginUserId, newFilename, file.getSize(), e);
            throw new BusinessException(ErrorCode.SERVER_ERROR, "图片保存失败，请稍后重试");
        }

        return MyResult.success(resolveBaseUrl(request) + "/upload/" + newFilename);
    }

    /**
     * 取小写后缀并卡进白名单。返回的是“归一化后的后缀”，后续签名比对与落盘都用它，
     * 避免“校验用的是 A、落盘用的是 B”这种两次解读
     */
    private String resolveSuffix(String originalFilename) {
        if (originalFilename == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件名不能为空");
        }
        int dotIndex = originalFilename.lastIndexOf(".");
        if (dotIndex < 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件缺少后缀名");
        }
        // Locale.ROOT 而不是无参 toLowerCase：后者跟着 JVM 默认区域走，土耳其语区下 "I" → "ı"，
        // .JPG 会变成 .ıpg 而落在白名单外 —— 同一个文件在两台服务器上一台能传一台不能，很难往代码上想
        String suffix = originalFilename.substring(dotIndex).toLowerCase(Locale.ROOT);
        if (!ImageContentUtils.isSupported(suffix)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "仅支持上传图片文件(" + ImageContentUtils.ALLOWED_SUFFIX_HINT + ")");
        }
        return suffix;
    }

    /**
     * 拼图片对外地址。
     *
     * 刻意不信 X-Forwarded-Host：那是客户端能随便写的头，而返回的 URL 会被存进 goods.imgUrl
     * 并让别的用户去加载 —— 信它就是让别人往攻击者的域名取“图片”。
     * 反代部署的唯一正确做法是把 upload.base-url 配成对外地址（application-prod.yaml 里已留好入口），
     * 而不是在代码里做“看起来智能”的头探测
     */
    private String resolveBaseUrl(HttpServletRequest request) {
        if (StringUtils.hasText(baseUrl)) {
            return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
        String scheme = request.getScheme();
        int port = request.getServerPort();
        // 端口是本协议默认值时不拼 ":80"/":443"：拼了也能用，但反代后的默认端口其实是内部端口，
        // 不拼至少不会把一个 8080 的地址固定下来
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
    }
}
