package com.sky.controller.user;

import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.sky.SkyApplication;
import com.sky.mapper.OrderMapper;
import com.sky.properties.JwtProperties;
import com.sky.service.OrderService;
import com.sky.task.MyTask;
import com.sky.task.OrderTask;
import com.sky.test.PaymentCryptoFixture;
import com.sky.utils.JwtUtil;
import com.sky.websocket.WebSocketServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real application HTTP/JWT/MySQL/transaction/WebSocket tests; DB must be an owned empty test schema. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Day10AcceptanceIT {
    private static final PaymentCryptoFixture CRYPTO = PaymentCryptoFixture.create();
    private static final String DATABASE = System.getProperty("day10.test.database", "");
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtProperties jwt;
    @Autowired private OrderMapper mapper;
    @Autowired private OrderService service;
    @Autowired private WebSocketServer server;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationContext context;
    @LocalServerPort private int port;
    private final String prefix = "d10" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private final List<Client> clients = new ArrayList<>();
    private long a, b, addressA, addressB;
    private HttpHeaders userA, userB;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        if (!DATABASE.matches("day10_verify_[a-f0-9]{12}")) throw new IllegalStateException("Run tools/verify_day10.ps1 with an isolated schema");
        String url = System.getenv("DAY10_TEST_JDBC_URL");
        if (url == null) url = "";
        if (!url.contains("/" + DATABASE + "?")) throw new IllegalStateException("Isolated JDBC URL does not match schema");
        final String isolatedUrl = url;
        registry.add("spring.datasource.druid.url", () -> isolatedUrl);
        registry.add("sky.wechat.appid", () -> "day10-test-app");
        registry.add("sky.wechat.mchid", () -> "day10-test-merchant");
        registry.add("sky.wechat.mch-serial-no", () -> CRYPTO.serial);
        registry.add("sky.wechat.private-key-file-path", () -> CRYPTO.privateKeyFile.toString());
        registry.add("sky.wechat.we-chat-pay-cert-file-path", () -> CRYPTO.certificateFile.toString());
        registry.add("sky.wechat.api-v3-key", () -> PaymentCryptoFixture.API_KEY);
        registry.add("sky.wechat.notify-url", () -> "https://example.invalid/notify/paySuccess");
    }

    @BeforeEach
    void prepare() {
        assertEquals(DATABASE, jdbc.queryForObject("select database()", String.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from orders", Integer.class));
        assertTrue(context.getBeansOfType(OrderTask.class).isEmpty(), "Automatic business tasks must be disabled");
        a = user(prefix + "a"); b = user(prefix + "b");
        addressA = address(a); addressB = address(b);
        userA = token(a); userB = token(b);
    }

    @AfterEach
    void cleanup() {
        clients.forEach(Client::close);
        if (a == 0 && b == 0) return;
        jdbc.update("delete from order_detail where order_id in (select id from orders where user_id in (?,?))", a, b);
        jdbc.update("delete from orders where user_id in (?,?)", a, b);
        jdbc.update("delete from address_book where user_id in (?,?)", a, b);
        jdbc.update("delete from user where id in (?,?)", a, b);
        assertEquals(0, jdbc.queryForObject("select count(*) from orders", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from user", Integer.class));
    }

    @AfterAll
    static void closeCertificates() throws Exception { CRYPTO.close(); }

    @EnableScheduling
    @Import(OrderTask.class)
    static class TaskOnlyConfig {
        @Bean OrderMapper isolatedMockMapper() { return mock(OrderMapper.class); }
    }

    @Test
    void schedulingIsEnabledAndTaskSwitchesRegisterOnlyIntendedMethods() throws Exception {
        assertTrue(SkyApplication.class.isAnnotationPresent(EnableScheduling.class));
        assertTrue(context.getBeansOfType(MyTask.class).isEmpty());
        assertTrue(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().isEmpty());
        assertFalse(CronExpression.isValidExpression("0 0 1 * * ? 2026"));
        for (String method : List.of("processTimeoutOrder", "processDeliveryOrder")) {
            Scheduled scheduled = OrderTask.class.getMethod(method).getAnnotation(Scheduled.class);
            assertTrue(CronExpression.isValidExpression(scheduled.cron()));
            assertEquals("Asia/Shanghai", scheduled.zone());
        }
        try (AnnotationConfigApplicationContext tasks = new AnnotationConfigApplicationContext()) {
            tasks.getEnvironment().getPropertySources().addFirst(new MapPropertySource("day10-task-check",
                    Map.of("sky.day10.order-task-enabled", "true", "sky.day10.demo-enabled", "false")));
            tasks.register(TaskOnlyConfig.class); tasks.refresh();
            assertEquals(2, tasks.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size());
        }
        try (AnnotationConfigApplicationContext demo = new AnnotationConfigApplicationContext()) {
            demo.getEnvironment().getPropertySources().addFirst(new MapPropertySource("day10-demo-check", Map.of("sky.day10.demo-enabled", "true")));
            demo.register(MyTask.class);
            demo.registerBean(ScheduledAnnotationBeanPostProcessor.class);
            demo.refresh();
            assertNotNull(demo.getBean(MyTask.class));
            assertEquals(1, demo.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size());
        }
    }

    @Test
    void timeoutSqlCancelsOnlyUnpaidPendingRowsStrictlyBeforeCutoff() {
        LocalDateTime now = now(), cutoff = now.minusMinutes(15);
        long expired = order(a, 1, 0, cutoff.minusSeconds(1));
        long equal = order(a, 1, 0, cutoff), newer = order(a, 1, 0, cutoff.plusSeconds(1));
        long paid = order(a, 1, 1, cutoff.minusHours(1)), accepted = order(a, 2, 1, cutoff.minusHours(1));
        Map<String, Object> equalBefore = row(equal), paidBefore = row(paid);
        assertEquals(1, mapper.cancelTimeoutOrders(cutoff, now));
        Map<String, Object> cancelled = row(expired);
        assertEquals(6, state(expired)); assertEquals(0, number(cancelled, "pay_status"));
        assertEquals("支付超时，自动取消", cancelled.get("cancel_reason"));
        assertEquals(now, jdbc.queryForObject("select cancel_time from orders where id=?", LocalDateTime.class, expired));
        assertEquals(3, number(cancelled, "pack_amount"));
        assertEquals(equalBefore, row(equal)); assertEquals(paidBefore, row(paid));
        assertEquals(1, state(newer)); assertEquals(2, state(accepted));
        assertEquals(0, mapper.cancelTimeoutOrders(cutoff, now.plusMinutes(1)));
        assertEquals(cancelled, row(expired));
    }

    @Test
    void deliverySqlCompletesOnlyOldPaidDeliveriesAndPreservesExistingTime() {
        LocalDateTime now = now(), cutoff = now.minusHours(1), oldTime = now.minusMinutes(30);
        long old = order(a, 4, 1, cutoff.minusSeconds(1)), equal = order(a, 4, 1, cutoff);
        long unpaid = order(a, 4, 0, cutoff.minusHours(1)), accepted = order(a, 3, 1, cutoff.minusHours(1));
        long recorded = order(a, 4, 1, cutoff.minusHours(1));
        jdbc.update("update orders set delivery_time=? where id=?", oldTime, recorded);
        assertEquals(2, mapper.completeDeliveryOrders(cutoff, now));
        assertEquals(5, state(old)); assertEquals(5, state(recorded));
        assertEquals(now, jdbc.queryForObject("select delivery_time from orders where id=?", LocalDateTime.class, old));
        assertEquals(oldTime, jdbc.queryForObject("select delivery_time from orders where id=?", LocalDateTime.class, recorded));
        assertEquals(4, state(equal)); assertEquals(4, state(unpaid)); assertEquals(3, state(accepted));
        assertEquals(3, number(row(old), "pack_amount"));
        assertEquals(0, mapper.completeDeliveryOrders(cutoff, now));
    }

    @Test
    void actualTaskMethodsExecuteRealUpdatesOnlyInTheIsolatedSchema() {
        long expired = order(a, 1, 0, now().minusMinutes(16));
        long fresh = order(a, 1, 0, now().minusMinutes(10));
        long delivered = order(a, 4, 1, now().minusMinutes(61));
        long newDelivery = order(a, 4, 1, now().minusMinutes(30));
        OrderTask task = new OrderTask(); ReflectionTestUtils.setField(task, "orderMapper", mapper);
        task.processTimeoutOrder(); task.processDeliveryOrder();
        assertEquals(6, state(expired)); assertEquals(1, state(fresh));
        assertEquals(5, state(delivered)); assertEquals(4, state(newDelivery));
    }

    @Test
    void realWebSocketsBroadcastToSameSidClientsAndSurviveOneDisconnect() throws Exception {
        Client one = connect("same-sid"), two = connect("same-sid");
        String first = "{\"type\":1,\"orderId\":42,\"content\":\"订单号：local\"}";
        server.sendToAllClient(first);
        assertEquals(first, one.next()); assertEquals(first, two.next());
        one.socket.sendText("test client message", true).get(5, TimeUnit.SECONDS);
        one.close();
        String second = "{\"type\":2,\"orderId\":42,\"content\":\"订单号：local\"}";
        server.sendToAllClient(second); assertEquals(second, two.next());
    }

    @Test
    void reminderHttpRequiresOwnerPaidActiveStateAndPushesOnlyTypeTwo() throws Exception {
        Client client = connect("reminder");
        for (int status : new int[]{2, 3, 4}) {
            long id = order(a, status, 1, now()); Map<String, Object> before = row(id);
            reminder(id, userA, 1);
            JSONObject message = JSONObject.parseObject(client.next());
            assertEquals(2, message.getIntValue("type")); assertEquals(id, message.getLongValue("orderId"));
            assertEquals("订单号：" + before.get("number"), message.getString("content"));
            assertEquals(before, row(id));
        }
        for (int status : new int[]{1, 5, 6}) reminder(order(a, status, 1, now()), userA, 0);
        for (int status : new int[]{2, 3, 4}) reminder(order(a, status, 0, now()), userA, 0);
        reminder(order(b, 3, 1, now()), userA, 0);
        reminder(Long.MAX_VALUE, userA, 0);
        long id = order(a, 3, 1, now()); reminder(id, userB, 0);
        ResponseEntity<String> unauthorized = http.exchange("/user/order/reminder/" + id, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, unauthorized.getStatusCode());
        assertNull(client.messages.poll(200, TimeUnit.MILLISECONDS), "Failed reminder must not push a message");
    }

    @Test
    void realDatabaseCommitDispatchesAfterCommitAndRollbackDoesNotDispatch() throws Exception {
        Client client = connect("transactions"); long id = order(a, 1, 0, now());
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.execute(status -> {
            service.paySuccess(transaction(id));
            assertEquals(2, state(id));
            assertTrue(client.messages.isEmpty(), "No event before outer transaction commits");
            status.setRollbackOnly(); return null;
        });
        assertEquals(1, state(id)); assertEquals(0, number(row(id), "pay_status"));
        assertNull(client.messages.poll(200, TimeUnit.MILLISECONDS));
        tx.execute(status -> {
            service.paySuccess(transaction(id));
            assertTrue(client.messages.isEmpty()); return null;
        });
        JSONObject message = JSONObject.parseObject(client.next());
        assertEquals(1, message.getIntValue("type")); assertEquals(id, message.getLongValue("orderId"));
        assertEquals(2, state(id)); assertEquals(1, number(row(id), "pay_status"));
    }

    @Test
    void signedPaymentHttpPushesOnceDuplicateAndInvalidNotificationsDoNotPush() throws Exception {
        Client client = connect("payment"); long id = order(a, 1, 0, now());
        String body = CRYPTO.notification(transaction(id), "TRANSACTION.SUCCESS");
        assertEquals(HttpStatus.NO_CONTENT, notify(body).getStatusCode());
        JSONObject message = JSONObject.parseObject(client.next());
        assertEquals(1, message.getIntValue("type")); assertEquals(id, message.getLongValue("orderId"));
        assertEquals(2, state(id));
        assertEquals(HttpStatus.NO_CONTENT, notify(body).getStatusCode());
        long mismatch = order(a, 1, 0, now()); JSONObject wrong = transaction(mismatch);
        wrong.getJSONObject("amount").put("total", 1);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, notify(CRYPTO.notification(wrong, "TRANSACTION.SUCCESS")).getStatusCode());
        assertEquals(1, state(mismatch));
        assertNull(client.messages.poll(250, TimeUnit.MILLISECONDS));
    }

    @Test
    void concurrentSignedPaymentNotificationsProduceOneCommittedEvent() throws Exception {
        Client client = connect("concurrent"); long id = order(a, 1, 0, now());
        String body = CRYPTO.notification(transaction(id), "TRANSACTION.SUCCESS");
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpStatus>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) results.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS)); return notify(body).getStatusCode();
            }));
            start.countDown();
            for (Future<HttpStatus> result : results) assertEquals(HttpStatus.NO_CONTENT, result.get(10, TimeUnit.SECONDS));
            assertEquals(id, JSONObject.parseObject(client.next()).getLongValue("orderId"));
            assertNull(client.messages.poll(250, TimeUnit.MILLISECONDS)); assertEquals(2, state(id));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    private static class Client implements WebSocket.Listener, AutoCloseable {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final StringBuilder buffer = new StringBuilder(); WebSocket socket;
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence text, boolean last) {
            buffer.append(text);
            if (last) { messages.offer(buffer.toString()); buffer.setLength(0); }
            webSocket.request(1); return CompletableFuture.completedFuture(null);
        }
        String next() throws InterruptedException { String text = messages.poll(5, TimeUnit.SECONDS); assertNotNull(text, "Expected WebSocket event"); return text; }
        @Override public void close() { if (socket != null) socket.abort(); }
    }

    private Client connect(String sid) throws Exception {
        Client client = new Client();
        client.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/" + sid), client).get(5, TimeUnit.SECONDS);
        clients.add(client); return client;
    }
    private long user(String openid) { jdbc.update("insert into user(openid,create_time) values (?,now())", openid); return jdbc.queryForObject("select id from user where openid=?", Long.class, openid); }
    private long address(long user) { jdbc.update("insert into address_book(user_id,consignee,phone,detail,is_default) values (?,'本地测试','13900139010','隔离测试地址',0)", user); return jdbc.queryForObject("select id from address_book where user_id=?", Long.class, user); }
    private long order(long user, int status, int paid, LocalDateTime time) {
        String number = prefix + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into orders(number,status,user_id,address_book_id,order_time,pay_method,pay_status,amount,pack_amount,tableware_number) values (?,?,?,?,?,1,?,13.50,3,2)",
                number, status, user, user == a ? addressA : addressB, time, paid);
        return jdbc.queryForObject("select id from orders where number=?", Long.class, number);
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0); }
    private Map<String, Object> row(long id) { return jdbc.queryForMap("select * from orders where id=?", id); }
    private int state(long id) { return jdbc.queryForObject("select status from orders where id=?", Integer.class, id); }
    private int number(Map<String, Object> row, String field) { return ((Number) row.get(field)).intValue(); }
    private HttpHeaders token(long user) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(jwt.getUserTokenName(), JwtUtil.createJWT(jwt.getUserSecretKey(), jwt.getUserTtl(), new HashMap<>(Map.of("userId", user)))); return headers;
    }
    private void reminder(long id, HttpHeaders headers, int code) {
        ResponseEntity<JsonNode> response = http.exchange("/user/order/reminder/" + id, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode()); assertNotNull(response.getBody());
        assertEquals(code, response.getBody().path("code").asInt(), String.valueOf(response.getBody()));
    }
    private JSONObject transaction(long id) {
        JSONObject t = new JSONObject(); t.put("appid", "day10-test-app"); t.put("mchid", "day10-test-merchant");
        t.put("trade_state", "SUCCESS"); t.put("out_trade_no", row(id).get("number"));
        t.put("transaction_id", "day10-local-transaction"); t.put("success_time", OffsetDateTime.now().withNano(0).toString());
        t.put("amount", JSONObject.parseObject("{\"total\":1350,\"currency\":\"CNY\"}"));
        JSONObject payer = new JSONObject(); payer.put("openid", prefix + "a"); t.put("payer", payer); return t;
    }
    private ResponseEntity<String> notify(String body) throws Exception {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        String timestamp = String.valueOf(Instant.now().getEpochSecond()), nonce = UUID.randomUUID().toString();
        headers.set("Wechatpay-Serial", CRYPTO.serial); headers.set("Wechatpay-Timestamp", timestamp); headers.set("Wechatpay-Nonce", nonce);
        headers.set("Wechatpay-Signature", CRYPTO.sign(timestamp + "\n" + nonce + "\n" + body + "\n"));
        return http.postForEntity("/notify/paySuccess", new HttpEntity<>(body, headers), String.class);
    }
}
