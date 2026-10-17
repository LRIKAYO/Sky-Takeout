package com.sky.utils;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AliOssUtilTest {

    @Test
    void usesV4AndReturnsConfiguredDomainAndReportsFailures() throws Exception {
        AtomicInteger status = new AtomicInteger(200);
        AtomicReference<String> errorCode = new AtomicReference<>("AccessDenied");
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            uploaded.set(exchange.getRequestBody().readAllBytes());
            if (status.get() == 200) {
                exchange.getResponseHeaders().set("ETag", "\"test-etag\"");
                exchange.sendResponseHeaders(200, -1);
            } else {
                byte[] error = ("<Error><Code>" + errorCode.get()
                        + "</Code><Message>Local test rejection</Message>"
                        + "<RequestId>local-test</RequestId></Error>")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/xml");
                exchange.sendResponseHeaders(status.get(), error.length);
                exchange.getResponseBody().write(error);
            }
            exchange.close();
        });
        server.start();
        try {
            String domain = "http://localhost:" + server.getAddress().getPort();
            AliOssUtil util = new AliOssUtil("oss-cn-hangzhou.aliyuncs.com",
                    "test-access-key", "test-secret", "test-bucket", "cn-hangzhou", domain + "/");
            byte[] image = new byte[]{1, 2, 3};

            assertEquals(domain + "/dish.png", util.upload(image, "dish.png"));
            // SDK 对 localhost 使用包含 Bucket 的路径，真实 CNAME 使用域名访问。
            assertEquals("/test-bucket/dish.png", path.get());
            assertArrayEquals(image, uploaded.get());
            assertTrue(authorization.get().startsWith("OSS4-HMAC-SHA256 "));
            assertTrue(authorization.get().contains("/cn-hangzhou/oss/aliyun_v4_request"));

            status.set(403);
            assertThrows(IllegalStateException.class, () -> util.upload(image, "denied.png"));

            status.set(400);
            errorCode.set("PublicEndpointForbidden");
            IllegalStateException domainError = assertThrows(IllegalStateException.class,
                    () -> util.upload(image, "domain.png"));
            assertTrue(domainError.getMessage().contains("SKY_OSS_DOMAIN"));

            AliOssUtil missingConfig = new AliOssUtil("oss-cn-hangzhou.aliyuncs.com",
                    "", "", "", "cn-hangzhou", "");
            IllegalStateException configError = assertThrows(IllegalStateException.class,
                    () -> missingConfig.upload(image, "missing.png"));
            assertTrue(configError.getMessage().contains(".env.local"));
        } finally {
            server.stop(0);
        }
    }
}
