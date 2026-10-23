package com.sky.utils;

import com.alibaba.fastjson.JSONObject;
import com.sky.properties.WeChatProperties;
import com.sky.test.PaymentCryptoFixture;
import com.wechat.pay.contrib.apache.httpclient.WechatPayHttpClientBuilder;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WeChatPayUtilTest {
    @Test
    void createsExactCentPayloadAndVerifiableMiniProgramSignature() throws Exception {
        try (PaymentCryptoFixture crypto = PaymentCryptoFixture.create()) {
            WeChatPayUtil util = spy(util(crypto));
            AtomicReference<JSONObject> request = new AtomicReference<>();
            doAnswer(inv -> {
                request.set(inv.getArgument(1));
                return JSONObject.parseObject("{\"prepay_id\":\"test-prepay\"}");
            }).when(util).post(eq(WeChatPayUtil.JSAPI), any(JSONObject.class));
            JSONObject result = util.pay("test-order", new BigDecimal("13.50"), "本地测试", "test-openid");
            assertEquals(1350, request.get().getJSONObject("amount").getIntValue("total"));
            assertEquals("CNY", request.get().getJSONObject("amount").getString("currency"));
            assertEquals("test-openid", request.get().getJSONObject("payer").getString("openid"));
            assertEquals("test-order", request.get().getString("out_trade_no"));
            assertEquals("test-app", request.get().getString("appid"));
            assertEquals("test-merchant", request.get().getString("mchid"));
            assertEquals("prepay_id=test-prepay", result.getString("package"));
            assertEquals("RSA", result.getString("signType"));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(crypto.certificate.getPublicKey());
            verifier.update(("test-app\n" + result.getString("timeStamp") + "\n" + result.getString("nonceStr")
                    + "\n" + result.getString("package") + "\n").getBytes(StandardCharsets.UTF_8));
            assertTrue(verifier.verify(Base64.getDecoder().decode(result.getString("paySign"))));
            assertThrows(ArithmeticException.class, () -> util.pay("test", new BigDecimal("0.001"), "test", "test"));
            assertThrows(IllegalArgumentException.class, () -> util.pay("test", BigDecimal.ZERO, "test", "test"));
            assertThrows(IllegalArgumentException.class, () -> util.pay("test", new BigDecimal("-1"), "test", "test"));
            assertThrows(ArithmeticException.class, () -> util.pay("test", new BigDecimal("21474836.48"), "test", "test"));
            verify(util, times(1)).post(anyString(), any());
            doReturn(new JSONObject()).when(util).post(eq(WeChatPayUtil.JSAPI), any());
            assertThrows(IllegalStateException.class, () -> util.pay("test", new BigDecimal("1.00"), "test", "test"));
        }
    }

    @Test
    void actualHttpClientSignsRequestsAndRejectsTamperedResponses() throws Exception {
        try (PaymentCryptoFixture crypto = PaymentCryptoFixture.create()) {
            WeChatPayUtil util = spy(util(crypto));
            // SDK 只对微信支付域名签名；测试仅通过 DNS 映射将该域名路由到回环服务。
            doAnswer(inv -> WechatPayHttpClientBuilder.create().withMerchant("test-merchant", crypto.serial, crypto.privateKey)
                    .withWechatPay(java.util.List.of(crypto.certificate)).setDnsResolver(host -> {
                        assertEquals("api.mch.weixin.qq.com", host);
                        return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
                    }).build()).when(util).client();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicReference<String> auth = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            server.createContext("/", exchange -> {
                try {
                    auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    exchange.getRequestBody().readAllBytes();
                    String body = "{\"prepay_id\":\"local-http-only\"}";
                    String timestamp = String.valueOf(Instant.now().getEpochSecond());
                    String nonce = "local-response-nonce";
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.getResponseHeaders().add("Request-ID", "local-test-response");
                    exchange.getResponseHeaders().add("Wechatpay-Serial", crypto.serial);
                    exchange.getResponseHeaders().add("Wechatpay-Timestamp", timestamp);
                    exchange.getResponseHeaders().add("Wechatpay-Nonce", nonce);
                    exchange.getResponseHeaders().add("Wechatpay-Signature", crypto.sign(timestamp + "\n" + nonce + "\n" + body + "\n"));
                    String sent = exchange.getRequestURI().getPath().equals("/tampered") ? body + " " : body;
                    byte[] bytes = sent.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/error") ? 400 : 200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Throwable ex) { failure.set(ex); }
                finally { exchange.close(); }
            });
            server.start();
            try {
                String base = "http://api.mch.weixin.qq.com:" + server.getAddress().getPort();
                assertEquals("local-http-only", util.post(base + "/ok", new JSONObject()).getString("prepay_id"));
                assertTrue(auth.get().startsWith("WECHATPAY2-SHA256-RSA2048"));
                assertThrows(Exception.class, () -> util.post(base + "/tampered", new JSONObject()));
                assertThrows(IllegalStateException.class, () -> util.post(base + "/error", new JSONObject()));
                assertNull(failure.get());
            } finally { server.stop(0); }
        }
    }

    @Test
    void preservesRefundAmountRulesWithoutCallingWechat() throws Exception {
        try (PaymentCryptoFixture crypto = PaymentCryptoFixture.create()) {
            WeChatPayUtil util = spy(util(crypto));
            doReturn(JSONObject.parseObject("{\"status\":\"PROCESSING\"}")).when(util).post(eq(WeChatPayUtil.REFUNDS), any());
            assertTrue(util.refund("test-order", "test-refund", new BigDecimal("1.50"), new BigDecimal("13.50")).contains("PROCESSING"));
            assertThrows(IllegalArgumentException.class, () -> util.refund("test", "test", new BigDecimal("2"), new BigDecimal("1")));
            assertThrows(IllegalArgumentException.class, () -> util.refund("test", "test", BigDecimal.ZERO, new BigDecimal("1")));
        }
    }

    private WeChatPayUtil util(PaymentCryptoFixture crypto) {
        WeChatProperties props = new WeChatProperties();
        props.setAppid("test-app");
        props.setMchid("test-merchant");
        props.setMchSerialNo(crypto.serial);
        props.setPrivateKeyFilePath(crypto.privateKeyFile.toString());
        props.setWeChatPayCertFilePath(crypto.certificateFile.toString());
        props.setNotifyUrl("https://example.invalid/notify/paySuccess");
        WeChatPayUtil util = new WeChatPayUtil();
        ReflectionTestUtils.setField(util, "properties", props);
        return util;
    }
}
