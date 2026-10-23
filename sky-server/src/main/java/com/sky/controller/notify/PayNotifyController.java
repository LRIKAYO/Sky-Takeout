package com.sky.controller.notify;

import com.alibaba.fastjson.JSON;
import com.sky.properties.WeChatProperties;
import com.sky.service.OrderService;
import com.wechat.pay.contrib.apache.httpclient.auth.CertificatesVerifier;
import com.wechat.pay.contrib.apache.httpclient.exception.ParseException;
import com.wechat.pay.contrib.apache.httpclient.exception.ValidationException;
import com.wechat.pay.contrib.apache.httpclient.notification.Notification;
import com.wechat.pay.contrib.apache.httpclient.notification.NotificationHandler;
import com.wechat.pay.contrib.apache.httpclient.notification.NotificationRequest;
import com.wechat.pay.contrib.apache.httpclient.util.PemUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/notify")
public class PayNotifyController {
    @Autowired private OrderService orderService;
    @Autowired private WeChatProperties properties;

    // 现有 JWT 仅匹配 /user/**、/admin/**；此入口使用微信签名认证，无需 IgnoreToken 注解。
    @PostMapping("/paySuccess")
    public ResponseEntity<?> paySuccess(
            @RequestHeader("Wechatpay-Serial") String serial,
            @RequestHeader("Wechatpay-Nonce") String nonce,
            @RequestHeader("Wechatpay-Timestamp") String timestamp,
            @RequestHeader("Wechatpay-Signature") String signature,
            HttpServletRequest servletRequest) {
        try {
            // 直接取原始报文，避开项目自定义 JSON 消息转换器，保证验签字节一致。
            String body = new String(servletRequest.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            long time = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (time < now - 300 || time > now + 300) return failure(400);
            if (properties.getApiV3Key() == null || properties.getApiV3Key().getBytes(StandardCharsets.UTF_8).length != 32 ||
                    properties.getWeChatPayCertFilePath() == null || properties.getWeChatPayCertFilePath().isBlank()) {
                return failure(500);
            }
            Notification notification;
            // 平台证书须在有效期内；本参考沿用工程的本地平台证书方式。
            try (InputStream certificate = Files.newInputStream(Paths.get(properties.getWeChatPayCertFilePath()))) {
                CertificatesVerifier verifier = new CertificatesVerifier(List.of(PemUtil.loadCertificate(certificate)));
                NotificationHandler handler = new NotificationHandler(verifier,
                        properties.getApiV3Key().getBytes(StandardCharsets.UTF_8));
                NotificationRequest request = new NotificationRequest.Builder().withSerialNumber(serial)
                        .withNonce(nonce).withTimestamp(timestamp).withSignature(signature).withBody(body).build();
                notification = handler.parse(request);
            }
            if (!"TRANSACTION.SUCCESS".equals(notification.getEventType())) return failure(400);
            orderService.paySuccess(JSON.parseObject(notification.getDecryptData()));
            // 业务事务已提交，才确认通知；重复成功通知也返回 204。
            return ResponseEntity.noContent().build();
        } catch (ValidationException | ParseException | IllegalArgumentException ex) {
            return failure(400);
        } catch (Exception ex) {
            // 业务或数据库暂时失败不确认成功，允许微信重试。
            return failure(500);
        }
    }

    private ResponseEntity<Map<String, String>> failure(int status) {
        return ResponseEntity.status(status).body(Map.of("code", "FAIL", "message", "支付通知处理失败"));
    }
}
