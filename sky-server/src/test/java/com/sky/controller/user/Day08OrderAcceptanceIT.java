package com.sky.controller.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 显式运行；真实 HTTP/JWT/MyBatis/MySQL，不调用支付或微信；仅操作随机夹具。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Day08OrderAcceptanceIT {
    private static final String URL = "/user/order/submit";
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtProperties jwt;
    @SpyBean private OrderDetailMapper details;
    @SpyBean private ShoppingCartMapper carts;
    private final String prefix = "d8order" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private Map<String, List<Map<String, Object>>> original;
    private long a, b, addressA, addressB, dish, meal;
    private HttpHeaders userA, userB;

    @BeforeEach
    void prepare() {
        assertEquals("sky_take_out_course", jdbc.queryForObject("select database()", String.class));
        original = snapshot();
        a = user(prefix + "a");
        b = user(prefix + "b");
        userA = token(a, jwt.getUserTtl());
        userB = token(b, jwt.getUserTtl());
        addressA = address(a);
        addressB = address(b);
        long dc = category(prefix + "dish", 1);
        long mc = category(prefix + "meal", 2);
        jdbc.update("insert into dish(name,category_id,price,status,image) values (?,?,6.50,1,?)", prefix + "dish", dc, "http://localhost:8080/uploads/test.png");
        dish = jdbc.queryForObject("select id from dish where name=?", Long.class, prefix + "dish");
        jdbc.update("insert into dish_flavor(dish_id,name,value) values (?, '辣度', '[\"微辣\",\"不辣\"]')", dish);
        jdbc.update("insert into setmeal(name,category_id,price,status,image) values (?,?,20.00,1,?)", prefix + "meal", mc, "http://localhost:8080/uploads/test.png");
        meal = jdbc.queryForObject("select id from setmeal where name=?", Long.class, prefix + "meal");
        jdbc.update("insert into setmeal_dish(setmeal_id,dish_id,name,price,copies) values (?,?,?,6.50,1)", meal, dish, prefix + "dish");
    }

    @AfterEach
    void cleanupAndVerifyOriginalData() {
        reset(details, carts);
        if (original == null) return;
        List<Long> users = jdbc.queryForList("select id from user where openid in (?,?)", Long.class, prefix + "a", prefix + "b");
        for (Long user : users) {
            jdbc.update("delete from order_detail where order_id in (select id from orders where user_id=?)", user);
            jdbc.update("delete from orders where user_id=?", user);
            jdbc.update("delete from shopping_cart where user_id=?", user);
            jdbc.update("delete from address_book where user_id=?", user);
            jdbc.update("delete from user where id=?", user);
        }
        jdbc.update("delete from setmeal_dish where setmeal_id in (select id from setmeal where name=?)", prefix + "meal");
        jdbc.update("delete from setmeal where name=?", prefix + "meal");
        jdbc.update("delete from dish_flavor where dish_id in (select id from dish where name=?)", prefix + "dish");
        jdbc.update("delete from dish where name=?", prefix + "dish");
        jdbc.update("delete from category where name in (?,?)", prefix + "dish", prefix + "meal");
        assertEquals(original, snapshot(), "夹具清理后全部原有业务行须保持一致，不比较自增计数");
    }

    @Test
    void submitsMixedCartAndPersistsOneOrderWithSnapshotsAndUserIsolation() {
        add(userA, Map.of("dishId", dish, "dishFlavor", "微辣"));
        add(userA, Map.of("dishId", dish, "dishFlavor", "微辣"));
        add(userA, Map.of("dishId", dish, "dishFlavor", "不辣"));
        add(userA, Map.of("setmealId", meal));
        add(userA, Map.of("setmealId", meal));
        add(userB, Map.of("dishId", dish));
        Map<String, Object> request = body(addressA, "70.50", 5);
        request.put("userId", b);
        request.put("status", 5);
        request.put("payStatus", 1);
        request.put("tablewareNumber", null);
        request.put("remark", "下单验收备注");
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        JsonNode vo = submit(request, userA, 1).path("data");
        long id = vo.path("id").asLong();
        Map<String, Object> order = jdbc.queryForMap("select * from orders where id=?", id);
        assertEquals(1, count("orders", a));
        assertEquals(a, ((Number) order.get("user_id")).longValue());
        assertEquals(addressA, ((Number) order.get("address_book_id")).longValue());
        assertEquals(new BigDecimal("70.50"), order.get("amount"));
        assertEquals(1, ((Number) order.get("status")).intValue());
        assertEquals(0, ((Number) order.get("pay_status")).intValue());
        assertNull(order.get("checkout_time"));
        assertEquals(5, ((Number) order.get("pack_amount")).intValue());
        assertEquals(0, ((Number) order.get("tableware_number")).intValue());
        assertEquals("下单验收备注", order.get("remark"));
        assertEquals("上海市上海市黄浦区测试路8号", order.get("address"));
        assertEquals("验收用户", order.get("consignee"));
        assertEquals("13800138000", order.get("phone"));
        assertEquals(order.get("number"), vo.path("orderNumber").asText());
        assertEquals(0, new BigDecimal(vo.path("orderAmount").asText()).compareTo(new BigDecimal("70.50")));
        LocalDateTime time = jdbc.queryForObject("select order_time from orders where id=?", LocalDateTime.class, id);
        assertTrue(time.isAfter(before) && time.isBefore(LocalDateTime.now().plusSeconds(1)));
        // 沿用工程 JacksonObjectMapper 的分钟精度，数据库仍保留秒。
        assertEquals(time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), vo.path("orderTime").asText());
        List<Map<String, Object>> rows = jdbc.queryForList("select * from order_detail where order_id=?", id);
        assertEquals(3, rows.size());
        assertEquals(5, rows.stream().mapToInt(r -> ((Number) r.get("number")).intValue()).sum());
        for (Map<String, Object> row : rows) {
            assertEquals(id, ((Number) row.get("order_id")).longValue());
            assertEquals("http://localhost:8080/uploads/test.png", row.get("image"));
            if (row.get("dish_id") != null) {
                assertEquals(dish, ((Number) row.get("dish_id")).longValue());
                assertNull(row.get("setmeal_id"));
                assertEquals(new BigDecimal("6.50"), row.get("amount"));
                assertTrue(List.of("微辣", "不辣").contains(row.get("dish_flavor")));
            } else {
                assertEquals(meal, ((Number) row.get("setmeal_id")).longValue());
                assertEquals(new BigDecimal("20.00"), row.get("amount"));
                assertEquals(2, ((Number) row.get("number")).intValue());
            }
        }
        assertEquals(0, count("shopping_cart", a));
        assertEquals(1, count("shopping_cart", b));
        jdbc.update("update address_book set detail='下单后更改的地址' where id=?", addressA);
        jdbc.update("update dish set price=99.00 where id=?", dish);
        assertEquals(order, jdbc.queryForMap("select * from orders where id=?", id));
        assertEquals(rows, jdbc.queryForList("select * from order_detail where order_id=?", id));
        submit(request, userA, 0);
        assertEquals(1, count("orders", a));
    }

    @Test
    void rejectsMissingForeignOrEmptyCartWithoutLosingData() {
        add(userA, Map.of("dishId", dish));
        List<Map<String, Object>> cart = cartRows();
        submit(body(addressB, "13.50", 1), userA, 0);
        submit(body(Long.MAX_VALUE, "13.50", 1), userA, 0);
        assertEquals(cart, cartRows());
        assertEquals(0, count("orders", a));
        submit(body(addressB, "13.50", 1), userB, 0);
        assertEquals(0, count("orders", b));
    }

    @Test
    void rejectsInvalidFieldsAndTamperedAmounts() {
        add(userA, Map.of("dishId", dish));
        List<Map<String, Object>> cart = cartRows();
        for (Map<String, Object> change : List.of(
                Map.<String, Object>of("addressBookId", 0), Map.<String, Object>of("payMethod", 2),
                Map.<String, Object>of("amount", "0.01"), Map.<String, Object>of("amount", "-1"),
                Map.<String, Object>of("amount", "13.501"), Map.<String, Object>of("packAmount", 0),
                Map.<String, Object>of("deliveryStatus", 3), Map.<String, Object>of("tablewareStatus", 3),
                Map.<String, Object>of("tablewareNumber", -1), Map.<String, Object>of("tablewareNumber", 1001),
                Map.<String, Object>of("remark", "x".repeat(101)),
                Map.<String, Object>of("deliveryStatus", 0, "estimatedDeliveryTime", "2000-01-01 00:00:00"))) {
            Map<String, Object> request = body(addressA, "13.50", 1);
            request.putAll(change);
            submit(request, userA, 0);
        }
        for (String key : List.of("addressBookId", "amount", "packAmount", "deliveryStatus", "tablewareStatus")) {
            Map<String, Object> request = body(addressA, "13.50", 1);
            request.remove(key);
            submit(request, userA, 0);
        }
        submit(Map.of(), userA, 0);
        assertEquals(cart, cartRows());
        assertEquals(0, count("orders", a));
    }

    @Test
    void supportsScheduledDeliveryAndExplicitTableware() {
        add(userA, Map.of("setmealId", meal));
        String expected = LocalDateTime.now().plusDays(1).withNano(0).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> request = body(addressA, "27.00", 1);
        request.putAll(Map.of("deliveryStatus", 0, "estimatedDeliveryTime", expected, "tablewareStatus", 0, "tablewareNumber", 2));
        long id = submit(request, userA, 1).path("data").path("id").asLong();
        assertEquals(0, jdbc.queryForObject("select delivery_status from orders where id=?", Integer.class, id));
        assertEquals(2, jdbc.queryForObject("select tableware_number from orders where id=?", Integer.class, id));
        assertEquals(expected, jdbc.queryForObject("select date_format(estimated_delivery_time,'%Y-%m-%d %H:%i:%s') from orders where id=?", String.class, id));
    }

    @Test
    void requiresValidUserJwtAndRejectsMalformedJson() {
        HttpHeaders bad = new HttpHeaders();
        bad.set(jwt.getUserTokenName(), "bad-token");
        HttpHeaders admin = new HttpHeaders();
        admin.set(jwt.getUserTokenName(), JwtUtil.createJWT(jwt.getAdminSecretKey(), jwt.getAdminTtl(), new HashMap<>(Map.of("empId", 1L))));
        for (HttpHeaders headers : List.of(new HttpHeaders(), bad, token(a, -1000), admin)) {
            assertEquals(HttpStatus.UNAUTHORIZED, http.exchange(URL, HttpMethod.POST,
                    new HttpEntity<>(body(addressA, "13.50", 1), headers), String.class).getStatusCode());
        }
        HttpHeaders json = new HttpHeaders();
        json.putAll(userA);
        json.setContentType(MediaType.APPLICATION_JSON);
        assertEquals(HttpStatus.BAD_REQUEST, http.exchange(URL, HttpMethod.POST, new HttpEntity<>("{bad-json", json), String.class).getStatusCode());
        assertEquals(0, count("orders", a));
    }

    @Test
    void rollsBackMasterAndDetailsWhenLaterStepsFail() {
        add(userA, Map.of("dishId", dish));
        List<Map<String, Object>> cart = cartRows();
        doThrow(new OrderBusinessException("验收注入：明细失败")).when(details).insertBatch(anyList());
        submit(body(addressA, "13.50", 1), userA, 0);
        assertEquals(0, count("orders", a));
        assertEquals(cart, cartRows());
        reset(details);
        doThrow(new OrderBusinessException("验收注入：清购物车失败")).when(carts).deleteByUserId(a);
        submit(body(addressA, "13.50", 1), userA, 0);
        assertEquals(0, count("orders", a));
        assertEquals(0, jdbc.queryForObject("select count(*) from order_detail where name=?", Integer.class, prefix + "dish"));
        assertEquals(cart, cartRows());
    }

    @Test
    void concurrentDuplicateSubmissionsProduceExactlyOneOrder() throws Exception {
        add(userA, Map.of("dishId", dish));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> requests = new ArrayList<>();
            for (int i = 0; i < 2; i++) requests.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                ResponseEntity<JsonNode> response = http.postForEntity(URL, new HttpEntity<>(body(addressA, "13.50", 1), userA), JsonNode.class);
                assertEquals(HttpStatus.OK, response.getStatusCode());
                return response.getBody().path("code").asInt();
            }));
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> request : requests) codes.add(request.get(10, TimeUnit.SECONDS));
            Collections.sort(codes);
            assertEquals(List.of(0, 1), codes);
            assertEquals(1, count("orders", a));
            assertEquals(0, count("shopping_cart", a));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsCorruptCartRowsWithoutCreatingOrders() {
        add(userA, Map.of("dishId", dish));
        for (int quantity : new int[]{0, -1, 1001}) {
            jdbc.update("update shopping_cart set number=? where user_id=?", quantity, a);
            submit(body(addressA, "13.50", 1), userA, 0);
        }
        jdbc.update("update shopping_cart set number=1, amount=0 where user_id=?", a);
        submit(body(addressA, "13.50", 1), userA, 0);
        assertEquals(0, count("orders", a));
        assertEquals(1, count("shopping_cart", a));
    }

    @Test
    void rejectsAddressSnapshotsExceedingOrderColumnLimits() {
        add(userA, Map.of("dishId", dish));
        List<Map<String, Object>> cart = cartRows();
        // 地址簿允许 50 字收货人，订单表只允许 32 字；不应变成 HTTP 500。
        jdbc.update("update address_book set consignee=? where id=?", "名".repeat(33), addressA);
        submit(body(addressA, "13.50", 1), userA, 0);
        jdbc.update("update address_book set consignee='验收用户',province_name=?,city_name=?,district_name=?,detail=? where id=?",
                "省".repeat(32), "市".repeat(32), "区".repeat(32), "路".repeat(200), addressA);
        submit(body(addressA, "13.50", 1), userA, 0);
        assertEquals(cart, cartRows());
        assertEquals(0, count("orders", a));
    }

    private long user(String name) {
        jdbc.update("insert into user(openid,create_time) values (?,now())", name);
        return jdbc.queryForObject("select id from user where openid=?", Long.class, name);
    }

    private long address(long user) {
        jdbc.update("insert into address_book(user_id,consignee,phone,sex,province_name,city_name,district_name,detail,is_default) values (?,'验收用户','13800138000','0','上海市','上海市','黄浦区','测试路8号',0)", user);
        return jdbc.queryForObject("select id from address_book where user_id=?", Long.class, user);
    }

    private long category(String name, int type) {
        jdbc.update("insert into category(name,type,status,sort) values (?,?,1,1)", name, type);
        return jdbc.queryForObject("select id from category where name=?", Long.class, name);
    }

    private HttpHeaders token(long user, long ttl) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(jwt.getUserTokenName(), JwtUtil.createJWT(jwt.getUserSecretKey(), ttl, new HashMap<>(Map.of("userId", user))));
        return headers;
    }

    private Map<String, Object> body(long address, String amount, int pack) {
        Map<String, Object> request = new HashMap<>();
        request.putAll(Map.of("addressBookId", address, "payMethod", 1, "amount", new BigDecimal(amount),
                "packAmount", pack, "deliveryStatus", 1, "tablewareStatus", 1));
        return request;
    }

    private JsonNode submit(Object body, HttpHeaders headers, int code) {
        ResponseEntity<JsonNode> response = http.postForEntity(URL, new HttpEntity<>(body, headers), JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), String.valueOf(response.getBody()));
        assertNotNull(response.getBody());
        assertEquals(code, response.getBody().path("code").asInt(), String.valueOf(response.getBody()));
        return response.getBody();
    }

    private void add(HttpHeaders user, Object body) {
        ResponseEntity<JsonNode> response = http.postForEntity("/user/shoppingCart/add", new HttpEntity<>(body, user), JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().path("code").asInt(), String.valueOf(response.getBody()));
    }

    private int count(String table, long user) {
        return jdbc.queryForObject("select count(*) from " + table + " where user_id=?", Integer.class, user);
    }

    private List<Map<String, Object>> cartRows() {
        return jdbc.queryForList("select * from shopping_cart where user_id=? order by id", a);
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("show tables", String.class)) rows.put(table, jdbc.queryForList("select * from `" + table + "` order by id"));
        return rows;
    }
}
