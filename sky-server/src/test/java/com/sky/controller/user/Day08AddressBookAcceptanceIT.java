package com.sky.controller.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.sky.exception.AddressBookBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 显式运行：真实 HTTP/JWT/MyBatis/MySQL；只创建、删除本轮随机用户与地址。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Day08AddressBookAcceptanceIT {
    private static final String URL = "/user/addressBook";
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtProperties jwt;
    @SpyBean private AddressBookMapper mapper;
    private final String prefix = "d8" + UUID.randomUUID().toString().replace("-", "");
    private Map<String, List<Map<String, Object>>> original;
    private long a;
    private long b;
    private HttpHeaders userA;
    private HttpHeaders userB;

    @BeforeEach
    void prepare() {
        assertEquals("sky_take_out_course", jdbc.queryForObject("select database()", String.class));
        original = snapshot();
        a = user(prefix + "a");
        b = user(prefix + "b");
        userA = token(a, jwt.getUserTtl());
        userB = token(b, jwt.getUserTtl());
    }

    @AfterEach
    void cleanup() {
        reset(mapper);
        if (original == null) return;
        jdbc.update("delete from address_book where user_id in (select id from user where openid in (?,?))",
                prefix + "a", prefix + "b");
        jdbc.update("delete from user where openid in (?,?)", prefix + "a", prefix + "b");
        assertEquals(original, snapshot(), "清理后全部业务表原有行应保持不变（不比较自增计数）");
    }

    @Test
    void sevenEndpointsPersistFullAddressAndDefaultLifecycle() {
        assertEquals(0, list(userA).size());
        call(HttpMethod.GET, URL + "/default", null, userA, 0);
        Map<String, Object> body = address("first");
        body.put("userId", b);
        body.put("isDefault", 1);
        body.put("id", Long.MAX_VALUE);
        call(HttpMethod.POST, URL, body, userA, 1);
        JsonNode row = list(userA).get(0);
        long first = row.path("id").asLong();
        assertNotEquals(Long.MAX_VALUE, first);
        assertEquals(a, row.path("userId").asLong());
        assertEquals(0, row.path("isDefault").asInt());
        assertEquals(0, list(userB).size());
        assertEquals("上海市", get(first, userA).path("provinceName").asText());

        Map<String, Object> edit = address("edited");
        edit.putAll(Map.of("id", first, "provinceCode", "32", "provinceName", "江苏省",
                "cityCode", "3201", "cityName", "南京市", "districtCode", "320102", "districtName", "玄武区",
                "userId", b, "isDefault", 1));
        call(HttpMethod.PUT, URL, edit, userA, 1);
        JsonNode changed = get(first, userA);
        for (String field : List.of("provinceCode", "provinceName", "cityCode", "cityName", "districtCode", "districtName")) {
            assertEquals(edit.get(field), changed.path(field).asText(), field);
        }
        assertEquals(a, changed.path("userId").asLong());
        assertEquals(0, changed.path("isDefault").asInt());
        long second = add("second", userA);
        call(HttpMethod.PUT, URL + "/default", Map.of("id", first), userA, 1);
        call(HttpMethod.PUT, URL + "/default", Map.of("id", second, "consignee", "must-not-overwrite"), userA, 1);
        call(HttpMethod.PUT, URL + "/default", Map.of("id", second), userA, 1);
        assertEquals(second, call(HttpMethod.GET, URL + "/default", null, userA, 1).path("data").path("id").asLong());
        assertEquals("second", get(second, userA).path("consignee").asText());
        assertEquals(0, get(first, userA).path("isDefault").asInt());
        assertEquals(1, defaults(a));
        call(HttpMethod.PUT, URL, Map.of("id", second, "detail", "修改门牌", "isDefault", 0), userA, 1);
        assertEquals(1, get(second, userA).path("isDefault").asInt());
        assertEquals("13800138000", get(second, userA).path("phone").asText());
        // 编译后的小程序删除路径带尾斜线，须与其保持兼容。
        call(HttpMethod.DELETE, URL + "/?id=" + second, null, userA, 1);
        call(HttpMethod.GET, URL + "/default", null, userA, 0);
        call(HttpMethod.DELETE, URL + "?id=" + first, null, userA, 1);
        assertEquals(0, list(userA).size());
    }

    @Test
    void everyIdOperationEnforcesOwnershipAndKeepsExistingDefault() {
        long own = add("own", userA);
        long other = add("other", userB);
        call(HttpMethod.PUT, URL + "/default", Map.of("id", own), userA, 1);
        call(HttpMethod.PUT, URL + "/default", Map.of("id", other), userB, 1);
        for (long id : new long[]{other, Long.MAX_VALUE}) {
            call(HttpMethod.GET, URL + "/" + id, null, userA, 0);
            call(HttpMethod.PUT, URL, Map.of("id", id, "detail", "forbidden", "userId", b), userA, 0);
            call(HttpMethod.DELETE, URL + "?id=" + id, null, userA, 0);
            call(HttpMethod.PUT, URL + "/default", Map.of("id", id, "userId", b), userA, 0);
        }
        assertEquals(own, call(HttpMethod.GET, URL + "/list?userId=" + b, null, userA, 1).path("data").get(0).path("id").asLong());
        assertEquals("other", get(other, userB).path("consignee").asText());
        assertEquals(1, defaults(a));
        assertEquals(1, defaults(b));
    }

    @Test
    void rejectsInvalidFieldsWithoutWritingRows() {
        for (Map<String, Object> change : List.of(Map.<String, Object>of("consignee", " "),
                Map.<String, Object>of("phone", "123"), Map.<String, Object>of("detail", "x".repeat(201)),
                Map.<String, Object>of("provinceName", "x".repeat(33)), Map.<String, Object>of("sex", "2"))) {
            Map<String, Object> invalid = address("invalid");
            invalid.putAll(change);
            call(HttpMethod.POST, URL, invalid, userA, 0);
        }
        call(HttpMethod.POST, URL, Map.of(), userA, 0);
        call(HttpMethod.PUT, URL, Map.of("detail", "missing-id"), userA, 0);
        call(HttpMethod.GET, URL + "/0", null, userA, 0);
        call(HttpMethod.DELETE, URL + "?id=-1", null, userA, 0);
        call(HttpMethod.PUT, URL + "/default", Map.of(), userA, 0);
        long own = add("valid", userA);
        call(HttpMethod.PUT, URL, Map.of("id", own, "phone", "invalid"), userA, 0);
        assertEquals("13800138000", get(own, userA).path("phone").asText());
        assertEquals(1, list(userA).size());
    }

    @Test
    void allSevenEndpointsRequireAValidUserJwt() {
        Object[][] routes = {{HttpMethod.GET, "/list", null}, {HttpMethod.GET, "/default", null},
                {HttpMethod.GET, "/1", null}, {HttpMethod.POST, "", address("unauth")},
                {HttpMethod.PUT, "", Map.of("id", 1)}, {HttpMethod.PUT, "/default", Map.of("id", 1)},
                {HttpMethod.DELETE, "?id=1", null}};
        HttpHeaders malformed = new HttpHeaders();
        malformed.set(jwt.getUserTokenName(), "bad-token");
        HttpHeaders admin = new HttpHeaders();
        admin.set(jwt.getUserTokenName(), JwtUtil.createJWT(jwt.getAdminSecretKey(), jwt.getAdminTtl(),
                new HashMap<>(Map.of("empId", 1L))));
        for (Object[] route : routes) {
            for (HttpHeaders headers : List.of(new HttpHeaders(), malformed, token(a, -1000), admin)) {
                assertEquals(HttpStatus.UNAUTHORIZED,
                        http.exchange(URL + route[1], (HttpMethod) route[0], new HttpEntity<>(route[2], headers), String.class).getStatusCode());
            }
        }
        assertEquals(0, list(userA).size());
    }

    @Test
    void restoresPreviousDefaultWhenSecondUpdateFails() {
        long first = add("first", userA);
        long second = add("second", userA);
        call(HttpMethod.PUT, URL + "/default", Map.of("id", first), userA, 1);
        doThrow(new AddressBookBusinessException("验收注入：第二步失败")).when(mapper).setDefault(second, a);
        call(HttpMethod.PUT, URL + "/default", Map.of("id", second), userA, 0);
        assertEquals(1, get(first, userA).path("isDefault").asInt());
        assertEquals(0, get(second, userA).path("isDefault").asInt());
        assertEquals(1, defaults(a));
    }

    @Test
    void concurrentSwitchesLeaveExactlyOneDefault() throws Exception {
        long first = add("first", userA);
        long second = add("second", userA);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> requests = new ArrayList<>();
            for (long id : new long[]{first, second}) {
                requests.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    call(HttpMethod.PUT, URL + "/default", Map.of("id", id), userA, 1);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> request : requests) request.get(10, TimeUnit.SECONDS);
            assertEquals(1, defaults(a));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private int defaults(long userId) {
        return jdbc.queryForObject("select count(*) from address_book where user_id=? and is_default=1", Integer.class, userId);
    }

    private long user(String openid) {
        jdbc.update("insert into user(openid,create_time) values (?,now())", openid);
        return jdbc.queryForObject("select id from user where openid=?", Long.class, openid);
    }

    private Map<String, Object> address(String name) {
        Map<String, Object> body = new HashMap<>();
        body.putAll(Map.of("consignee", name, "phone", "13800138000", "sex", "0", "detail", "测试路 8 号",
                "provinceCode", "31", "provinceName", "上海市", "cityCode", "3101", "cityName", "上海市",
                "districtCode", "310101", "districtName", "黄浦区"));
        body.put("label", "1");
        return body;
    }

    private long add(String name, HttpHeaders headers) {
        call(HttpMethod.POST, URL, address(name), headers, 1);
        for (JsonNode row : list(headers)) {
            if (name.equals(row.path("consignee").asText())) return row.path("id").asLong();
        }
        throw new AssertionError("未找到新增地址");
    }

    private JsonNode list(HttpHeaders headers) {
        return call(HttpMethod.GET, URL + "/list", null, headers, 1).path("data");
    }

    private JsonNode get(long id, HttpHeaders headers) {
        return call(HttpMethod.GET, URL + "/" + id, null, headers, 1).path("data");
    }

    private HttpHeaders token(long user, long ttl) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(jwt.getUserTokenName(), JwtUtil.createJWT(jwt.getUserSecretKey(), ttl,
                new HashMap<>(Map.of("userId", user))));
        return headers;
    }

    private JsonNode call(HttpMethod method, String path, Object body, HttpHeaders headers, int code) {
        ResponseEntity<JsonNode> response = http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), path);
        assertNotNull(response.getBody(), path);
        assertEquals(code, response.getBody().path("code").asInt(), path + ": " + response.getBody());
        return response.getBody();
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("show tables", String.class)) {
            rows.put(table, jdbc.queryForList("select * from `" + table + "` order by id"));
        }
        return rows;
    }
}
