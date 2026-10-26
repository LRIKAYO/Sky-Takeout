package com.sky.controller.user;

import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.sky.properties.JwtProperties;
import com.sky.service.DeliveryRangeChecker;
import com.sky.utils.JwtUtil;
import com.sky.utils.WeChatPayUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP/JWT/MyBatis/MySQL. Random fixtures only; payment/map network calls are replaced. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sky.wechat.appid=day09-test-app", "sky.wechat.mchid=day09-test-merchant",
        "sky.wechat.mch-serial-no=test-serial", "sky.wechat.private-key-file-path=test-key",
        "sky.wechat.we-chat-pay-cert-file-path=test-cert",
        "sky.wechat.api-v3-key=12345678901234567890123456789012",
        "sky.wechat.notify-url=https://example.invalid/payment",
        "sky.wechat.refund-notify-url=https://example.invalid/refund"})
class Day09OrderAcceptanceIT {
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtProperties jwt;
    @Autowired private DeliveryRangeChecker range;
    @MockBean private WeChatPayUtil payment;
    private final String prefix = "d9" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private Map<String, List<Map<String, Object>>> original;
    private final Map<String, Object> rangeConfig = new HashMap<>();
    private long a, b, addressA, addressB, dish, meal;
    private HttpHeaders userA, userB, admin;

    @BeforeEach
    void prepare() {
        assertEquals("sky_take_out_course", jdbc.queryForObject("select database()", String.class));
        original = snapshot();
        for (String field : List.of("http", "enabled", "shopAddress", "ak")) {
            rangeConfig.put(field, ReflectionTestUtils.getField(range, field));
        }
        ReflectionTestUtils.setField(range, "enabled", false);
        a = user(prefix + "a"); b = user(prefix + "b");
        addressA = address(a); addressB = address(b);
        userA = token(false, a, jwt.getUserTtl()); userB = token(false, b, jwt.getUserTtl());
        Long employee = jdbc.queryForObject("select min(id) from employee where status=1", Long.class);
        assertNotNull(employee);
        admin = token(true, employee, jwt.getAdminTtl());
        long dc = category(prefix + "dish", 1), mc = category(prefix + "meal", 2);
        jdbc.update("insert into dish(name,category_id,price,status,image) values (?,?,6.50,1,'test.png')", prefix + "dish", dc);
        dish = jdbc.queryForObject("select id from dish where name=?", Long.class, prefix + "dish");
        jdbc.update("insert into dish_flavor(dish_id,name,value) values (?,'辣度','[\"微辣\",\"不辣\"]')", dish);
        jdbc.update("insert into setmeal(name,category_id,price,status,image) values (?,?,20.00,1,'test.png')", prefix + "meal", mc);
        meal = jdbc.queryForObject("select id from setmeal where name=?", Long.class, prefix + "meal");
    }

    @AfterEach
    void cleanup() {
        rangeConfig.forEach((field, value) -> ReflectionTestUtils.setField(range, field, value));
        reset(payment);
        if (original == null) return;
        for (Long user : jdbc.queryForList("select id from user where openid in (?,?)", Long.class, prefix + "a", prefix + "b")) {
            jdbc.update("delete from order_detail where order_id in (select id from orders where user_id=?)", user);
            jdbc.update("delete from orders where user_id=?", user);
            jdbc.update("delete from shopping_cart where user_id=?", user);
            jdbc.update("delete from address_book where user_id=?", user);
            jdbc.update("delete from user where id=?", user);
        }
        jdbc.update("delete from setmeal where name=?", prefix + "meal");
        jdbc.update("delete from dish_flavor where dish_id=?", dish);
        jdbc.update("delete from dish where id=?", dish);
        jdbc.update("delete from category where name in (?,?)", prefix + "dish", prefix + "meal");
        Map<String, List<Map<String, Object>>> after = snapshot();
        assertEquals(original.keySet(), after.keySet(), "Business table names must be unchanged");
        for (String table : original.keySet()) {
            assertTrue(original.get(table).equals(after.get(table)), "Original rows changed in table " + table);
        }
    }

    @Test
    void userHistoryPaginatesOwnSnapshotsAndProtectsAllIdEndpoints() {
        long first = order(a, 1, 0), second = order(a, 2, 1), third = order(a, 5, 1), foreign = order(b, 1, 0);
        JsonNode page = call(HttpMethod.GET, "/user/order/historyOrders?page=1&pageSize=2", null, userA, 1).path("data");
        assertEquals(3, page.path("total").asInt());
        assertEquals(2, page.path("records").size());
        assertEquals(third, page.path("records").get(0).path("id").asLong());
        assertEquals(second, page.path("records").get(1).path("id").asLong());
        assertEquals(2, page.path("records").get(0).path("orderDetailList").size());
        JsonNode filtered = call(HttpMethod.GET, "/user/order/historyOrders?page=1&pageSize=10&status=2&userId=" + b, null, userA, 1).path("data");
        assertEquals(1, filtered.path("total").asInt());
        assertEquals(second, filtered.path("records").get(0).path("id").asLong());
        assertEquals(0, call(HttpMethod.GET, "/user/order/historyOrders?page=99&pageSize=10", null, userA, 1).path("data").path("records").size());
        JsonNode detail = call(HttpMethod.GET, "/user/order/orderDetail/" + first, null, userA, 1).path("data");
        assertEquals(a, detail.path("userId").asLong());
        assertEquals("历史地址快照", detail.path("address").asText());
        assertTrue(detail.path("orderDishes").asText().contains("*2;"));
        jdbc.update("update dish set name=?,price=99 where id=?", prefix + "changed", dish);
        assertEquals(detail, call(HttpMethod.GET, "/user/order/orderDetail/" + first, null, userA, 1).path("data"));
        for (long id : new long[]{foreign, Long.MAX_VALUE}) {
            call(HttpMethod.GET, "/user/order/orderDetail/" + id, null, userA, 0);
            call(HttpMethod.PUT, "/user/order/cancel/" + id, null, userA, 0);
            call(HttpMethod.POST, "/user/order/repetition/" + id, null, userA, 0);
        }
        verifyNoInteractions(payment);
    }

    @Test
    void adminSearchAndStatisticsMatchDatabaseWithCombinedFilters() {
        long id = order(a, 2, 1); order(a, 3, 1); order(a, 4, 1); order(b, 2, 1);
        String query = "/admin/order/conditionSearch?page=1&pageSize=10&number=" + prefix +
                "&phone=13900&status=2&beginTime=2000-01-01%2000:00:00&endTime=2099-01-01%2000:00:00&userId=" + Long.MAX_VALUE;
        // TestRestTemplate String URLs encode parameters itself; use URI for already encoded spaces.
        ResponseEntity<JsonNode> response = http.exchange(java.net.URI.create(query), HttpMethod.GET, new HttpEntity<>(admin), JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().path("code").asInt(), String.valueOf(response.getBody()));
        JsonNode page = response.getBody().path("data");
        assertEquals(2, page.path("total").asInt());
        JsonNode stats = call(HttpMethod.GET, "/admin/order/statistics", null, admin, 1).path("data");
        String[] fields = {"toBeConfirmed", "confirmed", "deliveryInProgress"};
        for (int i = 0; i < fields.length; i++) {
            assertEquals(jdbc.queryForObject("select count(*) from orders where status=?", Integer.class, i + 2), stats.path(fields[i]).asInt());
        }
        JsonNode detail = call(HttpMethod.GET, "/admin/order/details/" + id, null, admin, 1).path("data");
        assertEquals(2, detail.path("orderDetailList").size());
        assertEquals(0, call(HttpMethod.GET, "/admin/order/conditionSearch?page=1&pageSize=10&number=none" + prefix, null, admin, 1).path("data").path("total").asInt());
    }

    @Test
    void adminTransitionsOnlyAllowPaidTwoThreeFourFiveAndPreserveOtherFields() {
        long id = order(a, 2, 1), unpaid = order(a, 1, 0);
        call(HttpMethod.PUT, "/admin/order/confirm", Map.of("id", unpaid), admin, 0);
        call(HttpMethod.PUT, "/admin/order/delivery/" + id, null, admin, 0);
        call(HttpMethod.PUT, "/admin/order/complete/" + id, null, admin, 0);
        call(HttpMethod.PUT, "/admin/order/confirm", Map.of("id", id), admin, 1);
        assertEquals(3, status(id));
        call(HttpMethod.PUT, "/admin/order/confirm", Map.of("id", id), admin, 0);
        call(HttpMethod.PUT, "/user/order/cancel/" + id, null, userA, 0);
        call(HttpMethod.PUT, "/admin/order/delivery/" + id, null, admin, 1);
        assertEquals(4, status(id));
        call(HttpMethod.PUT, "/admin/order/complete/" + id, null, admin, 1);
        assertEquals(5, status(id));
        Map<String, Object> completed = row(id);
        assertNotNull(completed.get("delivery_time"));
        assertEquals(3, ((Number) completed.get("pack_amount")).intValue());
        assertEquals(new BigDecimal("42.00"), completed.get("amount"));
        call(HttpMethod.PUT, "/admin/order/complete/" + id, null, admin, 0);
        call(HttpMethod.PUT, "/admin/order/cancel", Map.of("id", id, "cancelReason", "不能取消完成订单"), admin, 0);
        assertEquals(completed, row(id));
        verifyNoInteractions(payment);
    }

    @Test
    void unpaidCancelIsOwnedIdempotentAndDoesNotRefund() {
        long id = order(a, 1, 0), other = order(b, 1, 0);
        call(HttpMethod.PUT, "/user/order/cancel/" + other, null, userA, 0);
        call(HttpMethod.PUT, "/user/order/cancel/" + id, null, userA, 1);
        Map<String, Object> cancelled = row(id);
        assertEquals(6, status(id)); assertEquals(0, payStatus(id));
        assertEquals("用户取消", cancelled.get("cancel_reason"));
        assertNotNull(cancelled.get("cancel_time"));
        call(HttpMethod.PUT, "/user/order/cancel/" + id, null, userA, 1);
        assertEquals(cancelled, row(id));
        assertEquals(1, status(other));
        verifyNoInteractions(payment);
    }

    @Test
    void paidUserCancelRejectionAndAdminCancelUseStoredFullAmountAndStableRefundNumber() throws Exception {
        refundResponse("PROCESSING");
        long userCancel = order(a, 2, 1), rejected = order(a, 2, 1), cancelled = order(a, 3, 1), unpaid = order(a, 1, 0);
        call(HttpMethod.PUT, "/user/order/cancel/" + userCancel, null, userA, 1);
        call(HttpMethod.PUT, "/admin/order/rejection", Map.of("id", rejected, "rejectionReason", "商品售罄"), admin, 1);
        call(HttpMethod.PUT, "/admin/order/cancel", Map.of("id", cancelled, "cancelReason", "无法配送"), admin, 1);
        call(HttpMethod.PUT, "/admin/order/cancel", Map.of("id", unpaid, "cancelReason", "顾客更改计划"), admin, 1);
        for (long id : new long[]{userCancel, rejected, cancelled}) {
            assertEquals(6, status(id)); assertEquals(2, payStatus(id));
            String number = (String) row(id).get("number");
            verify(payment).refund(number, number, new BigDecimal("42.00"), new BigDecimal("42.00"));
        }
        assertEquals("商品售罄", row(rejected).get("rejection_reason"));
        assertEquals("无法配送", row(cancelled).get("cancel_reason"));
        assertEquals(0, payStatus(unpaid));
        call(HttpMethod.PUT, "/user/order/cancel/" + userCancel, null, userA, 1);
        verify(payment, times(3)).refund(anyString(), anyString(), any(), any());
        // pay_status=2 here means entered the refund flow, not verified settlement.
    }

    @Test
    void refundErrorsAndInvalidReasonsDoNotMutateOrders() throws Exception {
        long id = order(a, 2, 1);
        Map<String, Object> before = row(id);
        for (String reason : new String[]{"", " ", "x".repeat(256)}) {
            call(HttpMethod.PUT, "/admin/order/rejection", Map.of("id", id, "rejectionReason", reason), admin, 0);
            call(HttpMethod.PUT, "/admin/order/cancel", Map.of("id", id, "cancelReason", reason), admin, 0);
        }
        verifyNoInteractions(payment);
        refundResponse("ABNORMAL");
        call(HttpMethod.PUT, "/user/order/cancel/" + id, null, userA, 0);
        assertEquals(before, row(id));
        doThrow(new IOException("test timeout")).when(payment).refund(anyString(), anyString(), any(), any());
        call(HttpMethod.PUT, "/admin/order/rejection", Map.of("id", id, "rejectionReason", "售罄"), admin, 0);
        assertEquals(before, row(id));
    }

    @Test
    void repetitionMergesCurrentCartPreservesFlavorAndRollsBackWholeBatchOnSoldOut() {
        long id = order(a, 5, 1);
        addCart();
        Map<String, Object> orderBefore = row(id);
        call(HttpMethod.POST, "/user/order/repetition/" + id, null, userA, 1);
        List<Map<String, Object>> rows = cart();
        assertEquals(2, rows.size());
        assertEquals(4, rows.stream().mapToInt(r -> ((Number) r.get("number")).intValue()).sum());
        Map<String, Object> dishRow = rows.stream().filter(r -> r.get("dish_id") != null).findFirst().orElseThrow();
        assertEquals(3, ((Number) dishRow.get("number")).intValue());
        assertEquals("微辣", dishRow.get("dish_flavor"));
        assertEquals(orderBefore, row(id));
        assertEquals(1, countOrders());
        jdbc.update("update setmeal set status=0 where id=?", meal);
        List<Map<String, Object>> beforeFailure = cart();
        call(HttpMethod.POST, "/user/order/repetition/" + id, null, userA, 0);
        assertEquals(beforeFailure, cart(), "Dish increment before sold-out meal must roll back with the whole repetition");
        call(HttpMethod.POST, "/user/order/repetition/" + id, null, userB, 0);
    }

    @Test
    void jwtRolesAndInvalidQueryParametersAreRejected() {
        long id = order(a, 2, 1);
        List<String> gets = List.of("/user/order/historyOrders?page=1&pageSize=10", "/user/order/orderDetail/" + id,
                "/admin/order/conditionSearch?page=1&pageSize=10", "/admin/order/statistics", "/admin/order/details/" + id);
        for (String url : gets) {
            assertEquals(HttpStatus.UNAUTHORIZED, raw(HttpMethod.GET, url, null, new HttpHeaders()).getStatusCode());
        }
        List<String> puts = List.of("/user/order/cancel/" + id, "/admin/order/confirm", "/admin/order/rejection",
                "/admin/order/cancel", "/admin/order/delivery/" + id, "/admin/order/complete/" + id);
        for (String url : puts) assertEquals(HttpStatus.UNAUTHORIZED, raw(HttpMethod.PUT, url, Map.of("id", id), new HttpHeaders()).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, raw(HttpMethod.POST, "/user/order/repetition/" + id, null, new HttpHeaders()).getStatusCode());
        HttpHeaders swapped = new HttpHeaders();
        swapped.set(jwt.getUserTokenName(), admin.getFirst(jwt.getAdminTokenName()));
        assertEquals(HttpStatus.UNAUTHORIZED, raw(HttpMethod.GET, gets.get(0), null, swapped).getStatusCode());
        swapped = new HttpHeaders();
        swapped.set(jwt.getAdminTokenName(), userA.getFirst(jwt.getUserTokenName()));
        assertEquals(HttpStatus.UNAUTHORIZED, raw(HttpMethod.GET, "/admin/order/statistics", null, swapped).getStatusCode());
        for (String suffix : List.of("page=0&pageSize=10", "page=1&pageSize=101", "page=1&pageSize=10&status=7")) {
            call(HttpMethod.GET, "/user/order/historyOrders?" + suffix, null, userA, 0);
        }
        assertEquals(HttpStatus.BAD_REQUEST, raw(HttpMethod.GET, "/user/order/orderDetail/not-a-number", null, userA).getStatusCode());
        assertEquals(2, status(id));
    }

    @Test
    void concurrentConfirmOnlyOneRequestAdvancesStatus() throws Exception {
        long id = order(a, 2, 1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                ResponseEntity<JsonNode> result = raw(HttpMethod.PUT, "/admin/order/confirm", Map.of("id", id), admin);
                assertEquals(HttpStatus.OK, result.getStatusCode());
                return result.getBody().path("code").asInt();
            }));
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> result : futures) codes.add(result.get(10, TimeUnit.SECONDS));
            Collections.sort(codes);
            assertEquals(List.of(0, 1), codes); assertEquals(3, status(id));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test
    void deliveryDistance4999And5000Pass5001FailsWithoutLosingCart() throws Exception {
        AtomicLong distance = new AtomicLong(4999);
        HttpClient fake = mock(HttpClient.class);
        when(fake.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            if (request.uri().getPath().contains("geocoding")) {
                return mapResponse("{\"status\":0,\"result\":{\"location\":{\"lat\":31.2,\"lng\":121.4}}}");
            }
            assertTrue(request.uri().getQuery().contains("origin=31.2,121.4"));
            assertTrue(request.uri().getQuery().contains("coord_type=bd09ll"));
            return mapResponse("{\"status\":0,\"result\":{\"routes\":[{\"distance\":" + distance.get() + "}]}}");
        });
        enableMap(fake);
        for (long meters : new long[]{4999, 5000, 5001}) {
            distance.set(meters); addCart();
            List<Map<String, Object>> before = cart(); int count = countOrders();
            call(HttpMethod.POST, "/user/order/submit", submitBody(), userA, meters <= 5000 ? 1 : 0);
            assertEquals(count + (meters <= 5000 ? 1 : 0), countOrders());
            if (meters > 5000) assertEquals(before, cart()); else assertTrue(cart().isEmpty());
        }
        verify(fake, times(9)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void mapTimeoutMissingConfigApiErrorAndNoRouteFailBeforeWritingOrder() throws Exception {
        addCart(); List<Map<String, Object>> before = cart();
        HttpClient fake = mock(HttpClient.class); enableMap(fake);
        when(fake.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("test timeout"));
        call(HttpMethod.POST, "/user/order/submit", submitBody(), userA, 0);
        reset(fake);
        HttpResponse<String> apiError = mapResponse("{\"status\":1}");
        when(fake.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(apiError);
        call(HttpMethod.POST, "/user/order/submit", submitBody(), userA, 0);
        HttpResponse<String> invalidJson = mapResponse("not-json");
        when(fake.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(invalidJson);
        call(HttpMethod.POST, "/user/order/submit", submitBody(), userA, 0);
        when(fake.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            return mapResponse(request.uri().getPath().contains("geocoding") ?
                    "{\"status\":0,\"result\":{\"location\":{\"lat\":31.2,\"lng\":121.4}}}" :
                    "{\"status\":0,\"result\":{\"routes\":[]}}");
        });
        call(HttpMethod.POST, "/user/order/submit", submitBody(), userA, 0);
        ReflectionTestUtils.setField(range, "ak", "");
        call(HttpMethod.POST, "/user/order/submit", submitBody(), userA, 0);
        assertEquals(0, countOrders()); assertEquals(before, cart());
    }

    private void enableMap(HttpClient fake) {
        ReflectionTestUtils.setField(range, "enabled", true);
        ReflectionTestUtils.setField(range, "shopAddress", "上海市测试门店");
        ReflectionTestUtils.setField(range, "ak", "day09-test-ak");
        ReflectionTestUtils.setField(range, "http", fake);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> mapResponse(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(body);
        return response;
    }

    private void refundResponse(String status) throws Exception {
        when(payment.refund(anyString(), anyString(), any(), any())).thenAnswer(call -> {
            JSONObject response = new JSONObject(); response.put("status", status);
            response.put("out_trade_no", call.getArgument(0)); response.put("out_refund_no", call.getArgument(1));
            JSONObject amount = new JSONObject(); amount.put("refund", ((BigDecimal) call.getArgument(2)).movePointRight(2));
            response.put("amount", amount); return response.toJSONString();
        });
    }

    private long order(long owner, int status, int payStatus) {
        String number = prefix + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into orders(number,status,user_id,address_book_id,order_time,pay_method,pay_status,amount,phone,address,consignee,pack_amount,tableware_number) " +
                "values (?,?,?,?,date_format(now(),'%Y-%m-%d %H:%i:00'),1,?,42.00,'13900139009','历史地址快照','验收用户',3,0)",
                number, status, owner, owner == a ? addressA : addressB, payStatus);
        long id = jdbc.queryForObject("select id from orders where number=?", Long.class, number);
        jdbc.update("insert into order_detail(order_id,dish_id,name,dish_flavor,number,amount,image) values (?,?,?,'微辣',2,6.50,'test.png')", id, dish, prefix + "dish");
        jdbc.update("insert into order_detail(order_id,setmeal_id,name,number,amount,image) values (?,?,?,1,20.00,'test.png')", id, meal, prefix + "meal");
        return id;
    }
    private long user(String openid) { jdbc.update("insert into user(openid,create_time) values (?,now())", openid); return jdbc.queryForObject("select id from user where openid=?", Long.class, openid); }
    private long address(long user) { jdbc.update("insert into address_book(user_id,consignee,phone,detail,is_default) values (?,'验收用户','13900139009','测试路9号',0)", user); return jdbc.queryForObject("select id from address_book where user_id=?", Long.class, user); }
    private long category(String name, int type) { jdbc.update("insert into category(name,type,status,sort) values (?,?,1,1)", name, type); return jdbc.queryForObject("select id from category where name=?", Long.class, name); }
    private Map<String, Object> row(long id) { return jdbc.queryForMap("select * from orders where id=?", id); }
    private int status(long id) { return jdbc.queryForObject("select status from orders where id=?", Integer.class, id); }
    private int payStatus(long id) { return jdbc.queryForObject("select pay_status from orders where id=?", Integer.class, id); }
    private int countOrders() { return jdbc.queryForObject("select count(*) from orders where user_id=?", Integer.class, a); }
    private List<Map<String, Object>> cart() { return jdbc.queryForList("select * from shopping_cart where user_id=? order by id", a); }
    private Map<String, Object> submitBody() { return Map.of("addressBookId", addressA, "payMethod", 1, "amount", new BigDecimal("13.50"), "packAmount", 1, "deliveryStatus", 1, "tablewareStatus", 1); }
    private void addCart() { call(HttpMethod.POST, "/user/shoppingCart/add", Map.of("dishId", dish, "dishFlavor", "微辣"), userA, 1); }
    private HttpHeaders token(boolean isAdmin, long id, long ttl) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(isAdmin ? jwt.getAdminTokenName() : jwt.getUserTokenName(), JwtUtil.createJWT(isAdmin ? jwt.getAdminSecretKey() : jwt.getUserSecretKey(), ttl, new HashMap<>(Map.of(isAdmin ? "empId" : "userId", id))));
        return headers;
    }
    private ResponseEntity<JsonNode> raw(HttpMethod method, String url, Object body, HttpHeaders headers) { return http.exchange(url, method, new HttpEntity<>(body, headers), JsonNode.class); }
    private JsonNode call(HttpMethod method, String url, Object body, HttpHeaders headers, int code) {
        ResponseEntity<JsonNode> response = raw(method, url, body, headers);
        assertEquals(HttpStatus.OK, response.getStatusCode(), String.valueOf(response.getBody()));
        assertNotNull(response.getBody()); assertEquals(code, response.getBody().path("code").asInt(), String.valueOf(response.getBody()));
        return response.getBody();
    }
    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("show tables", String.class)) rows.put(table, jdbc.queryForList("select * from `" + table + "` order by id"));
        return rows;
    }
}
