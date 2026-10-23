package com.sky.controller.user;

import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.OrderMapper;
import com.sky.properties.JwtProperties;
import com.sky.properties.WeChatProperties;
import com.sky.test.PaymentCryptoFixture;
import com.sky.utils.JwtUtil;
import com.sky.utils.WeChatPayUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 HTTP、SDK 验签解密、MySQL；仅预支付外呼用 Spy 截断，无实际扣款。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Day08PaymentAcceptanceIT {
    private static final PaymentCryptoFixture CRYPTO = PaymentCryptoFixture.create();
    private static final String PAY = "/user/order/payment";
    private static final String NOTIFY = "/notify/paySuccess";
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtProperties jwt;
    @Autowired private WeChatProperties properties;
    @SpyBean private WeChatPayUtil pay;
    @SpyBean private OrderMapper orders;
    private final String prefix = "d8pay" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private Map<String, List<Map<String, Object>>> original;
    private long a, b, address, order;
    private String number;
    private HttpHeaders userA, userB;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("sky.wechat.appid", () -> "local-test-app");
        registry.add("sky.wechat.mchid", () -> "local-test-merchant");
        registry.add("sky.wechat.mch-serial-no", () -> CRYPTO.serial);
        registry.add("sky.wechat.private-key-file-path", () -> CRYPTO.privateKeyFile.toString());
        registry.add("sky.wechat.we-chat-pay-cert-file-path", () -> CRYPTO.certificateFile.toString());
        registry.add("sky.wechat.api-v3-key", () -> PaymentCryptoFixture.API_KEY);
        registry.add("sky.wechat.notify-url", () -> "https://example.invalid/notify/paySuccess");
    }

    @BeforeEach
    void prepare() throws Exception {
        assertEquals("sky_take_out_course", jdbc.queryForObject("select database()", String.class));
        original = snapshot();
        a = user(prefix + "a");
        b = user(prefix + "b");
        userA = token(a, jwt.getUserTtl());
        userB = token(b, jwt.getUserTtl());
        jdbc.update("insert into address_book(user_id,consignee,phone,detail,is_default) values (?,'支付验收','13800138000','本地测试路8号',0)", a);
        address = jdbc.queryForObject("select id from address_book where user_id=?", Long.class, a);
        number = prefix + "order";
        jdbc.update("insert into orders(number,status,user_id,address_book_id,order_time,pay_method,pay_status,amount) values (?,1,?,?,now(),1,0,13.50)", number, a, address);
        order = jdbc.queryForObject("select id from orders where number=?", Long.class, number);
        JSONObject result = new JSONObject();
        result.put("timeStamp", String.valueOf(Instant.now().getEpochSecond()));
        result.put("nonceStr", "local-test-nonce");
        result.put("package", "prepay_id=local-test-prepay");
        result.put("signType", "RSA");
        result.put("paySign", "local-test-signature");
        doReturn(result).when(pay).pay(anyString(), any(BigDecimal.class), anyString(), anyString());
    }

    @AfterEach
    void cleanup() {
        reset(pay, orders);
        properties.setMchid("local-test-merchant");
        properties.setApiV3Key(PaymentCryptoFixture.API_KEY);
        if (original == null) return;
        jdbc.update("delete from orders where user_id in (select id from user where openid in (?,?))", prefix + "a", prefix + "b");
        jdbc.update("delete from address_book where user_id in (select id from user where openid in (?,?))", prefix + "a", prefix + "b");
        jdbc.update("delete from user where openid in (?,?)", prefix + "a", prefix + "b");
        assertEquals(original, snapshot(), "支付夹具清理后所有原有业务表行保持一致，不比较自增计数");
    }

    @AfterAll
    static void cleanupCertificates() throws Exception { CRYPTO.close(); }

    @Test
    void paymentUsesStoredAmountAndReturnsAllFiveFieldsWithoutMarkingPaid() throws Exception {
        assertEquals("local-test-app", properties.getAppid());
        assertEquals(CRYPTO.certificateFile.toString(), properties.getWeChatPayCertFilePath());
        Map<String, Object> before = row();
        JsonNode result = request(Map.of("orderNumber", number, "payMethod", 1, "amount", "0.01", "userId", b), userA, 1).path("data");
        assertEquals("prepay_id=local-test-prepay", result.path("packageStr").asText());
        assertEquals("local-test-signature", result.path("paySign").asText());
        assertEquals("RSA", result.path("signType").asText());
        assertFalse(result.path("nonceStr").asText().isBlank());
        assertFalse(result.path("timeStamp").asText().isBlank());
        verify(pay).pay(number, new BigDecimal("13.50"), "苍穹外卖订单", prefix + "a");
        assertEquals(before, row(), "预支付成功不能修改支付状态");
    }

    @Test
    void rejectsUnauthorizedForeignAndInvalidOrdersBeforeExternalCall() throws Exception {
        HttpHeaders bad = new HttpHeaders();
        bad.set(jwt.getUserTokenName(), "bad-token");
        HttpHeaders admin = new HttpHeaders();
        admin.set(jwt.getUserTokenName(), JwtUtil.createJWT(jwt.getAdminSecretKey(), jwt.getAdminTtl(), new HashMap<>(Map.of("empId", 1L))));
        for (HttpHeaders headers : List.of(new HttpHeaders(), bad, token(a, -1000), admin)) {
            assertEquals(HttpStatus.UNAUTHORIZED, http.exchange(PAY, HttpMethod.PUT,
                    new HttpEntity<>(Map.of("orderNumber", number, "payMethod", 1), headers), String.class).getStatusCode());
        }
        request(Map.of("orderNumber", number, "payMethod", 1), userB, 0);
        request(Map.of("orderNumber", "missing", "payMethod", 1), userA, 0);
        request(Map.of("orderNumber", number, "payMethod", 2), userA, 0);
        request(Map.of(), userA, 0);
        jdbc.update("update orders set status=2,pay_status=1 where id=?", order);
        request(Map.of("orderNumber", number, "payMethod", 1), userA, 0);
        jdbc.update("update orders set status=6,pay_status=0 where id=?", order);
        request(Map.of("orderNumber", number, "payMethod", 1), userA, 0);
        verify(pay, never()).pay(anyString(), any(), anyString(), anyString());
    }

    @Test
    void missingConfigurationAndProviderErrorsKeepOrderUnpaid() throws Exception {
        Map<String, Object> before = row();
        properties.setMchid("");
        request(Map.of("orderNumber", number, "payMethod", 1), userA, 0);
        properties.setMchid("local-test-merchant");
        properties.setApiV3Key("too-short");
        request(Map.of("orderNumber", number, "payMethod", 1), userA, 0);
        verify(pay, never()).pay(anyString(), any(), anyString(), anyString());
        properties.setApiV3Key(PaymentCryptoFixture.API_KEY);
        doThrow(new IOException("本地注入：支付网络异常")).when(pay).pay(anyString(), any(), anyString(), anyString());
        request(Map.of("orderNumber", number, "payMethod", 1), userA, 0);
        doReturn(JSONObject.parseObject("{\"code\":\"ORDERPAID\"}")).when(pay).pay(anyString(), any(), anyString(), anyString());
        request(Map.of("orderNumber", number, "payMethod", 1), userA, 0);
        doReturn(JSONObject.parseObject("{\"package\":\"prepay_id=bad\",\"paySign\":\"bad\"}")).when(pay).pay(anyString(), any(), anyString(), anyString());
        request(Map.of("orderNumber", number, "payMethod", 1), userA, 0);
        assertEquals(before, row());
    }

    @Test
    void signedEncryptedHttpNotificationUpdatesDatabaseWithoutUserJwtAndIsIdempotent() throws Exception {
        String body = CRYPTO.notification(transaction(), "TRANSACTION.SUCCESS");
        assertEquals(HttpStatus.NO_CONTENT, notify(body, headers(body)).getStatusCode());
        Map<String, Object> paid = row();
        assertEquals(2, ((Number) paid.get("status")).intValue());
        assertEquals(1, ((Number) paid.get("pay_status")).intValue());
        assertNotNull(paid.get("checkout_time"));
        assertEquals(new BigDecimal("13.50"), paid.get("amount"));
        assertEquals(HttpStatus.NO_CONTENT, notify(body, headers(body)).getStatusCode());
        assertEquals(paid, row());
        jdbc.update("update orders set status=3 where id=?", order);
        assertEquals(HttpStatus.NO_CONTENT, notify(body, headers(body)).getStatusCode());
        assertEquals(3, ((Number) row().get("status")).intValue());
        verify(orders, times(1)).markPaid(any());
    }

    @Test
    void rejectsTamperedExpiredMalformedOrUndecryptableNotifications() throws Exception {
        Map<String, Object> before = row();
        String body = CRYPTO.notification(transaction(), "TRANSACTION.SUCCESS");
        assertEquals(HttpStatus.BAD_REQUEST, notify(body + " ", headers(body)).getStatusCode());
        HttpHeaders expired = headers(body);
        expired.set("Wechatpay-Timestamp", "1");
        assertEquals(HttpStatus.BAD_REQUEST, notify(body, expired).getStatusCode());
        HttpHeaders probe = headers(body);
        probe.set("Wechatpay-Signature", "WECHATPAY/SIGNTEST/local-invalid-signature");
        assertEquals(HttpStatus.BAD_REQUEST, notify(body, probe).getStatusCode());
        HttpHeaders unknown = headers(body);
        unknown.set("Wechatpay-Serial", "12345");
        assertEquals(HttpStatus.BAD_REQUEST, notify(body, unknown).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, notify(body, new HttpHeaders()).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, notify("{bad-json", headers("{bad-json")).getStatusCode());
        JSONObject broken = JSONObject.parseObject(body);
        broken.getJSONObject("resource").put("ciphertext", "AAAA");
        String invalidCipher = broken.toJSONString();
        assertEquals(HttpStatus.BAD_REQUEST, notify(invalidCipher, headers(invalidCipher)).getStatusCode());
        String wrongEvent = CRYPTO.notification(transaction(), "TRANSACTION.CLOSED");
        assertEquals(HttpStatus.BAD_REQUEST, notify(wrongEvent, headers(wrongEvent)).getStatusCode());
        assertEquals(before, row());
        verify(orders, never()).markPaid(any());
    }

    @Test
    void signedButMismatchedBusinessDataIsRejectedWithoutWriting() throws Exception {
        Map<String, Object> before = row();
        for (String field : List.of("appid", "mchid", "out_trade_no", "trade_state", "transaction_id", "amount", "currency", "payer", "success_time")) {
            JSONObject t = transaction();
            switch (field) {
                case "amount": t.getJSONObject("amount").put("total", 1); break;
                case "currency": t.getJSONObject("amount").put("currency", "USD"); break;
                case "payer": t.getJSONObject("payer").put("openid", prefix + "b"); break;
                case "transaction_id": t.remove(field); break;
                default: t.put(field, "wrong");
            }
            String body = CRYPTO.notification(t, "TRANSACTION.SUCCESS");
            ResponseEntity<String> response = notify(body, headers(body));
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(), field);
            assertTrue(response.getBody().contains("FAIL"));
            assertEquals(before, row(), field);
        }
        jdbc.update("update orders set status=6 where id=?", order);
        String body = CRYPTO.notification(transaction(), "TRANSACTION.SUCCESS");
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, notify(body, headers(body)).getStatusCode());
        assertEquals(6, ((Number) row().get("status")).intValue());
        assertEquals(0, ((Number) row().get("pay_status")).intValue());
    }

    @Test
    void concurrentDuplicateNotificationsUpdateOnceAndDatabaseFailureCanRetry() throws Exception {
        String body = CRYPTO.notification(transaction(), "TRANSACTION.SUCCESS");
        doAnswer(inv -> { inv.callRealMethod(); throw new OrderBusinessException("本地注入：状态写入后失败"); }).when(orders).markPaid(any());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, notify(body, headers(body)).getStatusCode());
        assertEquals(1, ((Number) row().get("status")).intValue());
        assertEquals(0, ((Number) row().get("pay_status")).intValue());
        assertNull(row().get("checkout_time"));
        reset(orders);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpStatus>> responses = new ArrayList<>();
            for (int i = 0; i < 2; i++) responses.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return notify(body, headers(body)).getStatusCode();
            }));
            start.countDown();
            for (Future<HttpStatus> response : responses) assertEquals(HttpStatus.NO_CONTENT, response.get(10, TimeUnit.SECONDS));
            verify(orders, times(1)).markPaid(any());
            assertEquals(1, ((Number) row().get("pay_status")).intValue());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private JSONObject transaction() {
        JSONObject t = new JSONObject();
        t.put("appid", "local-test-app"); t.put("mchid", "local-test-merchant");
        t.put("trade_state", "SUCCESS"); t.put("out_trade_no", number); t.put("transaction_id", "local-test-transaction");
        t.put("success_time", OffsetDateTime.now().withNano(0).toString());
        t.put("amount", JSONObject.parseObject("{\"total\":1350,\"currency\":\"CNY\"}"));
        JSONObject payer = new JSONObject(); payer.put("openid", prefix + "a"); t.put("payer", payer);
        return t;
    }

    private HttpHeaders headers(String body) throws Exception {
        HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        h.set("Wechatpay-Serial", CRYPTO.serial); h.set("Wechatpay-Timestamp", timestamp); h.set("Wechatpay-Nonce", nonce);
        h.set("Wechatpay-Signature", CRYPTO.sign(timestamp + "\n" + nonce + "\n" + body + "\n"));
        return h;
    }

    private ResponseEntity<String> notify(String body, HttpHeaders headers) {
        return http.postForEntity(NOTIFY, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode request(Object body, HttpHeaders headers, int code) {
        ResponseEntity<JsonNode> r = http.exchange(PAY, HttpMethod.PUT, new HttpEntity<>(body, headers), JsonNode.class);
        assertEquals(HttpStatus.OK, r.getStatusCode()); assertNotNull(r.getBody());
        assertEquals(code, r.getBody().path("code").asInt(), String.valueOf(r.getBody()));
        return r.getBody();
    }

    private HttpHeaders token(long user, long ttl) {
        HttpHeaders h = new HttpHeaders();
        h.set(jwt.getUserTokenName(), JwtUtil.createJWT(jwt.getUserSecretKey(), ttl, new HashMap<>(Map.of("userId", user))));
        return h;
    }

    private long user(String openid) {
        jdbc.update("insert into user(openid,create_time) values (?,now())", openid);
        return jdbc.queryForObject("select id from user where openid=?", Long.class, openid);
    }

    private Map<String, Object> row() { return jdbc.queryForMap("select * from orders where id=?", order); }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("show tables", String.class)) rows.put(table, jdbc.queryForList("select * from `" + table + "` order by id"));
        return rows;
    }
}
