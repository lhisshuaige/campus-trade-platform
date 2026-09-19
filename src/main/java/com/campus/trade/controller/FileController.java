package com.campus.trade.controller;

import com.campus.trade.bean.exception.BusinessException;
import com.campus.trade.bean.exception.ErrorCode;
import com.campus.trade.bean.DTO.result.MyResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/file")
@Tag(name = "文件上传接口")
public class FileController {

    @Value("${upload.path}")
    private String uploadPath;

    // 允许上传的图片后缀
    private static final List<String> ALLOWED_SUFFIX = Arrays.asList(".jpg", ".jpeg", ".png", ".gif", ".webp");

    @PostMapping("/upload")
    @Operation(summary = "上传图片")
    public MyResult<String> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择文件");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件名不能为空");
        }

        int dotIndex = originalFilename.lastIndexOf(".");
        if (dotIndex < 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件缺少后缀名");
        }
        String suffix = originalFilename.substring(dotIndex).toLowerCase();
        if (!ALLOWED_SUFFIX.contains(suffix)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "仅支持上传图片文件(jpg/jpeg/png/gif/webp)");
        }

        String newFilename = UUID.randomUUID().toString().replace("-", "") + suffix;

        File dir = new File(uploadPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "创建上传目录失败");
        }

        try {
            file.transferTo(new File(dir.getAbsolutePath() + File.separator + newFilename));
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "文件上传失败: " + e.getMessage());
        }

        String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        String fileUrl = baseUrl + "/upload/" + newFilename;
        return MyResult.success(fileUrl);
    }
}
