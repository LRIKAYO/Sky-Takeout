package com.sky.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.sky.properties.JwtProperties;
import com.sky.task.OrderTask;
import com.sky.utils.JwtUtil;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/JWT/MySQL; only the empty schema owned by the completion verification script. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectCompletionAcceptanceIT {
    private static final String DATABASE = System.getProperty("day12.test.database", "");
    private static final String PASSWORD_PATH = "/admin/employee/editPassword";
    private static final String LOGOUT_PATH = "/user/user/logout";
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtProperties jwt;
    @Autowired private ApplicationContext context;
    private final String prefix = "complete_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private long employee, other, user;
    private HttpHeaders admin, customer;
    private boolean owned;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        String url = System.getenv("DAY12_TEST_JDBC_URL");
        if (!DATABASE.matches("day12_verify_[a-f0-9]{12}") || url == null || !url.contains("/" + DATABASE + "?")) {
            throw new IllegalStateException("Run tools/verify_project_completion.ps1 with its owned schema");
        }
        registry.add("spring.datasource.druid.url", () -> url);
    }

    @BeforeEach
    void prepare() {
        assertEquals(DATABASE, jdbc.queryForObject("select database()", String.class));
        assertTrue(context.getBeansOfType(OrderTask.class).isEmpty());
        for (String table : List.of("employee", "user", "shopping_cart")) {
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class));
        }
        owned = true;
        employee = employee("owner", "Start123");
        other = employee("other", "Other123");
        admin = headers(jwt.getAdminTokenName(), login("Start123").path("data").path("token").asText());
        jdbc.update("insert into user(openid,create_time) values (?,now())", prefix);
        user = jdbc.queryForObject("select id from user where openid=?", Long.class, prefix);
        customer = headers(jwt.getUserTokenName(), token(false, jwt.getUserTtl()));
    }

    @AfterEach
    void cleanup() {
        if (!owned) return;
        assertEquals(DATABASE, jdbc.queryForObject("select database()", String.class));
        jdbc.update("delete from user where id=? and openid=?", user, prefix);
        jdbc.update("delete from employee where username in (?,?)", prefix + "owner", prefix + "other");
        for (String table : List.of("employee", "user", "shopping_cart")) {
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class));
        }
    }

    @Test
    void passwordChangeChecksOwnershipOldPasswordAndInputThenPreservesOtherFields() {
        Map<String, Object> original = row(employee), untouched = row(other);
        List<Map<String, Object>> invalid = new ArrayList<>();
        invalid.add(Map.of("empId", employee, "oldPassword", "Wrong123", "newPassword", "Changed123"));
        invalid.add(Map.of("empId", other, "oldPassword", "Other123", "newPassword", "Changed123"));
        invalid.add(Map.of("empId", employee, "oldPassword", "Start123"));
        invalid.add(Map.of("empId", employee, "oldPassword", "", "newPassword", "Changed123"));
        for (String value : List.of("short", "a".repeat(21), "space pwd", "Start123")) {
            invalid.add(Map.of("empId", employee, "oldPassword", "Start123", "newPassword", value));
        }
        for (Map<String, Object> body : invalid) {
            assertEquals(0, request(HttpMethod.PUT, PASSWORD_PATH, body, admin).path("code").asInt());
            assertEquals(original, row(employee)); assertEquals(untouched, row(other));
        }
        assertEquals(HttpStatus.BAD_REQUEST, http.exchange(PASSWORD_PATH, HttpMethod.PUT,
                new HttpEntity<>(admin), JsonNode.class).getStatusCode());
        assertEquals(1, request(HttpMethod.PUT, PASSWORD_PATH,
                Map.of("oldPassword", "Start123", "newPassword", "Changed123"), admin).path("code").asInt());
        Map<String, Object> changed = row(employee);
        assertEquals(hash("Changed123"), changed.get("password"));
        assertEquals(employee, ((Number) changed.get("update_user")).longValue());
        assertNotEquals(original.get("update_time"), changed.get("update_time"));
        for (String field : original.keySet()) {
            if (!List.of("password", "update_time", "update_user").contains(field)) {
                assertEquals(original.get(field), changed.get(field), field);
            }
        }
        assertEquals(untouched, row(other));
        assertEquals(0, login("Start123").path("code").asInt());
        assertEquals(1, login("Changed123").path("code").asInt());
    }

    @Test
    void bothEndpointsRejectMissingExpiredInvalidAndWrongRoleTokens() {
        Map<String, Object> before = row(employee);
        for (String value : List.of("", "invalid-token", token(true, -60000), token(false, jwt.getUserTtl()))) {
            HttpHeaders invalid = headers(jwt.getAdminTokenName(), value);
            assertEquals(HttpStatus.UNAUTHORIZED, http.exchange(PASSWORD_PATH, HttpMethod.PUT,
                    new HttpEntity<>(Map.of("empId", employee, "oldPassword", "Start123", "newPassword", "Changed123"), invalid),
                    JsonNode.class).getStatusCode());
        }
        for (String value : List.of("", "invalid-token", token(false, -60000), token(true, jwt.getAdminTtl()))) {
            assertEquals(HttpStatus.UNAUTHORIZED, http.exchange(LOGOUT_PATH, HttpMethod.POST,
                    new HttpEntity<>(headers(jwt.getUserTokenName(), value)), JsonNode.class).getStatusCode());
        }
        assertEquals(before, row(employee));
        assertEquals(1, request(HttpMethod.POST, LOGOUT_PATH, null, customer).path("code").asInt());
    }

    @Test
    void concurrentChangesUsingTheSameOldPasswordHaveExactlyOneWinner() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<JsonNode>> results = new ArrayList<>();
            for (String password : List.of("Alpha123", "Beta123")) {
                results.add(executor.submit(() -> { start.await(); return change(password); }));
            }
            start.countDown();
            int first = results.get(0).get(15, TimeUnit.SECONDS).path("code").asInt();
            int second = results.get(1).get(15, TimeUnit.SECONDS).path("code").asInt();
            assertEquals(1, first + second);
            String winner = first == 1 ? "Alpha123" : "Beta123";
            assertEquals(hash(winner), row(employee).get("password"));
            assertEquals(1, login(winner).path("code").asInt());
            assertEquals(0, login(first == 1 ? "Beta123" : "Alpha123").path("code").asInt());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void logoutMatchesExistingStatelessJwtContractWithoutDeletingUserData() {
        Map<String, Object> before = jdbc.queryForMap("select * from user where id=?", user);
        assertEquals(1, request(HttpMethod.POST, LOGOUT_PATH, null, customer).path("code").asInt());
        assertEquals(1, request(HttpMethod.POST, LOGOUT_PATH, null, customer).path("code").asInt());
        assertEquals(before, jdbc.queryForMap("select * from user where id=?", user));
        // Course logout acknowledges the request; the client clears its token. No revocation store is claimed.
        assertEquals(1, request(HttpMethod.GET, "/user/shoppingCart/list", null, customer).path("code").asInt());
    }

    private long employee(String suffix, String password) {
        jdbc.update("insert into employee(name,username,password,phone,sex,id_number,status,create_time,update_time,create_user,update_user) "
                + "values (?,?,?,'13900000000','1','000000000000000000',1,'2020-01-01','2020-01-01',1,1)",
                prefix + suffix, prefix + suffix, hash(password));
        return jdbc.queryForObject("select id from employee where username=?", Long.class, prefix + suffix);
    }
    private Map<String, Object> row(long id) { return jdbc.queryForMap("select * from employee where id=?", id); }
    private static String hash(String password) { return DigestUtils.md5DigestAsHex(password.getBytes(StandardCharsets.UTF_8)); }
    private HttpHeaders headers(String name, String value) {
        HttpHeaders result = new HttpHeaders(); if (!value.isEmpty()) result.set(name, value); return result;
    }
    private String token(boolean administrator, long ttl) {
        return JwtUtil.createJWT(administrator ? jwt.getAdminSecretKey() : jwt.getUserSecretKey(), ttl,
                new HashMap<>(Map.of(administrator ? "empId" : "userId", administrator ? employee : user)));
    }
    private JsonNode login(String password) {
        return request(HttpMethod.POST, "/admin/employee/login", Map.of("username", prefix + "owner", "password", password), new HttpHeaders());
    }
    private JsonNode change(String password) {
        return request(HttpMethod.PUT, PASSWORD_PATH,
                Map.of("empId", employee, "oldPassword", "Start123", "newPassword", password), admin);
    }
    private JsonNode request(HttpMethod method, String path, Object body, HttpHeaders headers) {
        ResponseEntity<JsonNode> response = http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode()); assertNotNull(response.getBody()); return response.getBody();
    }
}
