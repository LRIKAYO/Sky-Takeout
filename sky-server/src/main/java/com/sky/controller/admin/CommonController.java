package com.sky.controller.admin;

import com.sky.constant.MessageConstant;
import com.sky.result.Result;
import com.sky.utils.AliOssUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/admin/common")
@Api(tags = "通用接口")
@Slf4j
public class CommonController {

    @Autowired
    private AliOssUtil aliOssUtil;

    @Value("${sky.upload.mode}")
    private String uploadMode;

    @Value("${sky.upload.local-dir}")
    private String localDir;

    @Value("${sky.upload.base-url}")
    private String baseUrl;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ApiOperation("文件上传")
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.error("请选择要上传的图片");
        }

        String filename = file.getOriginalFilename();
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        if (dot < 0) {
            return Result.error("图片文件名缺少后缀");
        }

        String extension = filename.substring(dot).toLowerCase(Locale.ROOT);
        if (!Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp").contains(extension)) {
            return Result.error("不支持的图片后缀");
        }

        String objectName = UUID.randomUUID().toString() + extension;
        try {
            if ("local".equalsIgnoreCase(uploadMode)) {
                Path directory = Paths.get(localDir).toAbsolutePath().normalize();
                Files.createDirectories(directory);
                try (InputStream input = file.getInputStream()) {
                    Files.copy(input, directory.resolve(objectName));
                }
                return Result.success(baseUrl.replaceAll("/+$", "") + "/" + objectName);
            }
            if ("oss".equalsIgnoreCase(uploadMode)) {
                return Result.success(aliOssUtil.upload(file.getBytes(), objectName));
            }
            throw new IllegalStateException("SKY_UPLOAD_MODE 只能配置为 local 或 oss");
        } catch (IllegalStateException e) {
            log.warn("图片上传未完成：{}", e.getMessage());
            return Result.error(e.getMessage());
        } catch (IOException | RuntimeException e) {
            log.error("文件上传失败", e);
            return Result.error(MessageConstant.UPLOAD_FAILED);
        }
    }
}
