package com.sky.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.sky.properties.JwtProperties;
import com.sky.task.MyTask;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real login/HTTP/JWT/MyBatis/MySQL. Only this run's owned empty schema may be written. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Day11ReportAcceptanceIT {
    private static final String DATABASE = System.getProperty("day11.test.database", "");
    private static final List<String> PATHS = List.of("turnoverStatistics", "userStatistics", "ordersStatistics", "top10");
    private static final LocalDate BEGIN = LocalDate.of(2026, 10, 20);
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtProperties jwt;
    @Autowired private ApplicationContext context;
    private final String prefix = "d11" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private HttpHeaders admin;
    private long owner, employee;
    private int sequence;
    private boolean owned;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        if (!DATABASE.matches("day11_verify_[a-f0-9]{12}")) {
            throw new IllegalStateException("Run tools/verify_day11.ps1 with an owned isolated schema");
        }
        String url = System.getenv("DAY11_TEST_JDBC_URL");
        if (url == null || !url.contains("/" + DATABASE + "?")) {
            throw new IllegalStateException("Isolated JDBC URL does not match schema");
        }
        registry.add("spring.datasource.druid.url", () -> url);
    }

    @BeforeEach
    void prepare() {
        assertEquals(DATABASE, jdbc.queryForObject("select database()", String.class));
        assertTrue(context.getBeansOfType(OrderTask.class).isEmpty());
        assertTrue(context.getBeansOfType(MyTask.class).isEmpty());
        for (String table : List.of("employee", "user", "orders", "order_detail")) {
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class));
        }
        owned = true;
        String password = "day11-local-test-only";
        jdbc.update("insert into employee(name,username,password,phone,sex,id_number,status) values (?,?,?,'13900000000','1','000000000000000000',1)",
                prefix, prefix, DigestUtils.md5DigestAsHex(password.getBytes(StandardCharsets.UTF_8)));
        employee = jdbc.queryForObject("select id from employee where username=?", Long.class, prefix);
        ResponseEntity<JsonNode> login = http.postForEntity("/admin/employee/login",
                Map.of("username", prefix, "password", password), JsonNode.class);
        assertEquals(HttpStatus.OK, login.getStatusCode());
        assertNotNull(login.getBody());
        assertEquals(1, login.getBody().path("code").asInt());
        String token = login.getBody().path("data").path("token").asText();
        assertFalse(token.isBlank());
        admin = new HttpHeaders();
        admin.set(jwt.getAdminTokenName(), token);
    }

    @AfterEach
    void cleanup() {
        if (!owned) return;
        assertTrue(DATABASE.matches("day11_verify_[a-f0-9]{12}"));
        assertEquals(DATABASE, jdbc.queryForObject("select database()", String.class));
        // The guards above and the empty-schema assertion permit clearing only our own fixtures.
        for (String table : List.of("order_detail", "orders", "user", "employee")) {
            jdbc.update("delete from " + table);
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class));
        }
    }

    @Test
    void allFourReportsMatchTheTutorialFixtureThroughRealHttp() {
        user(BEGIN.minusDays(2).atStartOfDay()); user(BEGIN.minusDays(1).atStartOfDay());
        user(BEGIN.atStartOfDay()); user(BEGIN.plusDays(2).atStartOfDay()); user(BEGIN.plusDays(2).atTime(12, 0));
        long first = order(5, "10.00", BEGIN.atTime(10, 0)); detail(first, "鱼香肉丝", 2, false);
        long second = order(5, "20.00", BEGIN.atTime(11, 0));
        detail(second, "鱼香肉丝", 1, false); detail(second, "套餐A", 1, true);
        long cancelled = order(6, "99.00", BEGIN.atTime(12, 0)); detail(cancelled, "不应出现", 99, false);
        long pending = order(2, "50.00", BEGIN.plusDays(1).atTime(12, 0)); detail(pending, "不应出现", 50, true);
        long last = order(5, "25.00", BEGIN.plusDays(2).atTime(12, 0));
        detail(last, "鱼香肉丝", 2, false); detail(last, "套餐A", 1, true);
        JsonNode turnover = report("turnoverStatistics", BEGIN, BEGIN.plusDays(2));
        assertEquals("2026-10-20,2026-10-21,2026-10-22", turnover.path("dateList").asText());
        moneySeries(turnover.path("turnoverList").asText(), "30.00", "0", "25.00");
        JsonNode users = report("userStatistics", BEGIN, BEGIN.plusDays(2));
        assertEquals("1,0,2", users.path("newUserList").asText());
        assertEquals("3,3,5", users.path("totalUserList").asText());
        JsonNode orders = report("ordersStatistics", BEGIN, BEGIN.plusDays(2));
        assertEquals("3,1,1", orders.path("orderCountList").asText());
        assertEquals("2,0,1", orders.path("validOrderCountList").asText());
        assertEquals(5, orders.path("totalOrderCount").asInt());
        assertEquals(3, orders.path("validOrderCount").asInt());
        assertEquals(0.6, orders.path("orderCompletionRate").asDouble(), 0.000001);
        JsonNode top = report("top10", BEGIN, BEGIN.plusDays(2));
        assertEquals("鱼香肉丝,套餐A", top.path("nameList").asText());
        assertEquals("5,2", top.path("numberList").asText());
    }

    @Test
    void orderTimeBoundsIncludeStartAndLastSecondButExcludeNextMidnight() {
        LocalDate date = LocalDate.of(2026, 3, 31);
        for (LocalDateTime time : List.of(date.atStartOfDay().minusSeconds(1), date.plusDays(1).atStartOfDay())) {
            detail(order(5, "999.00", time), "区间外", 99, false);
        }
        detail(order(5, "0.10", date.atStartOfDay()), "边界菜", 2, false);
        detail(order(5, "0.20", date.plusDays(1).atStartOfDay().minusSeconds(1)), "边界菜", 3, false);
        moneySeries(report("turnoverStatistics", date, date).path("turnoverList").asText(), "0.30");
        assertEquals("2", report("ordersStatistics", date, date).path("orderCountList").asText());
        JsonNode top = report("top10", date, date);
        assertEquals("边界菜", top.path("nameList").asText());
        assertEquals("5", top.path("numberList").asText());
    }

    @Test
    void userMidnightBoundariesAndPriorRegistrationsMatchRealCountSql() {
        user(BEGIN.atStartOfDay().minusSeconds(1));
        user(BEGIN.atStartOfDay());
        user(BEGIN.plusDays(1).atStartOfDay().minusSeconds(1));
        user(BEGIN.plusDays(1).atStartOfDay());
        user(BEGIN.plusDays(2).atStartOfDay());
        JsonNode data = report("userStatistics", BEGIN, BEGIN.plusDays(1));
        assertEquals("2,1", data.path("newUserList").asText());
        assertEquals("3,4", data.path("totalUserList").asText());
    }

    @Test
    void allStatesCountTowardTotalButOnlyCompletedCountsTowardRevenueAndSales() {
        for (int state = 1; state <= 6; state++) {
            detail(order(state, state + ".00", BEGIN.atTime(12, 0)), "状态" + state, state, false);
        }
        JsonNode data = report("ordersStatistics", BEGIN, BEGIN);
        assertEquals(6, data.path("totalOrderCount").asInt());
        assertEquals(1, data.path("validOrderCount").asInt());
        assertEquals(1.0 / 6, data.path("orderCompletionRate").asDouble(), 0.000001);
        moneySeries(report("turnoverStatistics", BEGIN, BEGIN).path("turnoverList").asText(), "5");
        assertEquals("状态5", report("top10", BEGIN, BEGIN).path("nameList").asText());
    }

    @Test
    void emptyDataPreservesAllDatesAndZeroRateWithoutInventingProducts() {
        String dates = "2026-10-20,2026-10-21,2026-10-22";
        for (String path : PATHS) {
            JsonNode data = report(path, BEGIN, BEGIN.plusDays(2));
            if (!path.equals("top10")) assertEquals(dates, data.path("dateList").asText());
            if (path.equals("turnoverStatistics")) moneySeries(data.path("turnoverList").asText(), "0", "0", "0");
            if (path.equals("userStatistics")) {
                assertEquals("0,0,0", data.path("newUserList").asText());
                assertEquals("0,0,0", data.path("totalUserList").asText());
            }
            if (path.equals("ordersStatistics")) {
                assertEquals("0,0,0", data.path("orderCountList").asText());
                assertEquals("0,0,0", data.path("validOrderCountList").asText());
                assertEquals(0, data.path("totalOrderCount").asInt());
                assertEquals(0, data.path("validOrderCount").asInt());
                assertEquals(0.0, data.path("orderCompletionRate").asDouble());
            }
            if (path.equals("top10")) {
                assertEquals("", data.path("nameList").asText());
                assertEquals("", data.path("numberList").asText());
            }
        }
    }

    @Test
    void completionRateIsWeightedAcrossTheSelectedInterval() {
        for (int i = 0; i < 2; i++) order(i == 0 ? 5 : 6, "1.00", BEGIN.atTime(12, 0));
        for (int i = 0; i < 8; i++) order(i == 0 ? 5 : 1, "1.00", BEGIN.plusDays(1).atTime(12, 0));
        JsonNode data = report("ordersStatistics", BEGIN, BEGIN.plusDays(1));
        assertEquals("2,8", data.path("orderCountList").asText());
        assertEquals("1,1", data.path("validOrderCountList").asText());
        assertEquals(10, data.path("totalOrderCount").asInt());
        assertEquals(2, data.path("validOrderCount").asInt());
        assertEquals(0.2, data.path("orderCompletionRate").asDouble(), 0.000001);
    }

    @Test
    void top10AggregatesQuantitiesSortsAndTruncatesTwelveGroupsToTen() {
        long id = order(5, "10.00", BEGIN.atTime(12, 0));
        for (int i = 0; i < 12; i++) detail(id, String.format("G%02d", i), i + 1, i % 2 == 0);
        JsonNode top = report("top10", BEGIN, BEGIN);
        assertEquals("G11,G10,G09,G08,G07,G06,G05,G04,G03,G02", top.path("nameList").asText());
        assertEquals("12,11,10,9,8,7,6,5,4,3", top.path("numberList").asText());
    }

    @Test
    void top10KeepsCourseSameNameGroupingAndHistoricalSnapshots() {
        long one = order(5, "10.00", BEGIN.atTime(12, 0));
        long two = order(5, "20.00", BEGIN.atTime(13, 0));
        detail(one, "同名商品", 2, false); detail(two, "同名商品", 3, true);
        detail(two, "历史名称", 4, false);
        detail(order(4, "999.00", BEGIN.atTime(14, 0)), "未完成", 999, true);
        JsonNode top = report("top10", BEGIN, BEGIN);
        assertEquals("同名商品,历史名称", top.path("nameList").asText());
        assertEquals("5,4", top.path("numberList").asText());
    }

    @Test
    void equalSalesUseStableAscendingNames() {
        long id = order(5, "10.00", BEGIN.atTime(12, 0));
        detail(id, "B", 9, false); detail(id, "A", 9, true);
        JsonNode top = report("top10", BEGIN, BEGIN);
        assertEquals("A,B", top.path("nameList").asText());
        assertEquals("9,9", top.path("numberList").asText());
    }

    @Test
    void everyEndpointRejectsMissingMalformedReversedAndTooLongDates() {
        for (String path : PATHS) {
            for (String query : List.of("", "?begin=2026-10-20", "?end=2026-10-20",
                    "?begin=bad-date&end=2026-10-20", "?begin=2026-02-30&end=2026-03-01")) {
                assertEquals(HttpStatus.BAD_REQUEST, get(path, query, admin).getStatusCode(), path + query);
            }
            for (String query : List.of("?begin=2026-10-21&end=2026-10-20",
                    "?begin=2024-01-01&end=2025-01-01")) {
                ResponseEntity<JsonNode> response = get(path, query, admin);
                assertEquals(HttpStatus.OK, response.getStatusCode());
                assertNotNull(response.getBody());
                assertEquals(0, response.getBody().path("code").asInt());
            }
        }
    }

    @Test
    void leapDayCrossYearAnd366DayLimitWorkThroughHttp() {
        JsonNode leap = report("ordersStatistics", LocalDate.of(2024, 2, 28), LocalDate.of(2024, 3, 1));
        assertEquals("2024-02-28,2024-02-29,2024-03-01", leap.path("dateList").asText());
        assertEquals("2024-02-29", report("userStatistics", LocalDate.of(2024, 2, 29), LocalDate.of(2024, 2, 29)).path("dateList").asText());
        JsonNode year = report("turnoverStatistics", LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1));
        assertEquals("2025-12-31,2026-01-01", year.path("dateList").asText());
        JsonNode full = report("turnoverStatistics", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        assertEquals(366, full.path("dateList").asText().split(",").length);
        assertEquals(366, full.path("turnoverList").asText().split(",").length);
    }

    @Test
    void allEndpointsRequireValidAdminJwtFromRealLogin() {
        String user = JwtUtil.createJWT(jwt.getUserSecretKey(), jwt.getUserTtl(), new HashMap<>(Map.of("userId", 1L)));
        String expired = JwtUtil.createJWT(jwt.getAdminSecretKey(), -60000, new HashMap<>(Map.of("empId", employee)));
        for (String path : PATHS) {
            String query = "?begin=2026-10-20&end=2026-10-20";
            assertEquals(HttpStatus.UNAUTHORIZED, get(path, query, new HttpHeaders()).getStatusCode());
            for (String token : List.of(user, expired, "invalid-token")) {
                HttpHeaders invalid = new HttpHeaders(); invalid.set(jwt.getAdminTokenName(), token);
                assertEquals(HttpStatus.UNAUTHORIZED, get(path, query, invalid).getStatusCode());
            }
            assertEquals(HttpStatus.OK, get(path, query, admin).getStatusCode());
        }
    }

    @Test
    void reportsUseOrderTimeRatherThanPaymentOrDeliveryTimeAndRemainReadOnly() {
        long inside = order(5, "10.00", BEGIN.atTime(12, 0)); detail(inside, "区间内", 2, false);
        long outside = order(5, "999.00", BEGIN.minusDays(1).atTime(12, 0)); detail(outside, "区间外", 999, false);
        jdbc.update("update orders set checkout_time=?,delivery_time=? where id=?",
                BEGIN.plusDays(1).atTime(12, 0), BEGIN.plusDays(1).atTime(13, 0), inside);
        jdbc.update("update orders set checkout_time=?,delivery_time=? where id=?",
                BEGIN.atTime(12, 0), BEGIN.atTime(13, 0), outside);
        Map<String, List<Map<String, Object>>> before = snapshot();
        moneySeries(report("turnoverStatistics", BEGIN, BEGIN).path("turnoverList").asText(), "10.00");
        assertEquals(1, report("ordersStatistics", BEGIN, BEGIN).path("totalOrderCount").asInt());
        assertEquals("区间内", report("top10", BEGIN, BEGIN).path("nameList").asText());
        report("userStatistics", BEGIN, BEGIN);
        assertEquals(before, snapshot(), "All business rows must remain unchanged after report reads");
    }

    private ResponseEntity<JsonNode> get(String path, String query, HttpHeaders headers) {
        return http.exchange("/admin/report/" + path + query, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private JsonNode report(String path, LocalDate begin, LocalDate end) {
        ResponseEntity<JsonNode> response = get(path, "?begin=" + begin + "&end=" + end, admin);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode body = response.getBody(); assertNotNull(body);
        assertEquals(1, body.path("code").asInt(), String.valueOf(body));
        assertTrue(body.path("data").isObject());
        return body.path("data");
    }

    private long user(LocalDateTime time) {
        String openid = prefix + "u" + (++sequence);
        jdbc.update("insert into user(openid,create_time) values (?,?)", openid, time);
        long id = jdbc.queryForObject("select id from user where openid=?", Long.class, openid);
        if (owner == 0) owner = id;
        return id;
    }

    private long order(int state, String amount, LocalDateTime time) {
        if (owner == 0) user(LocalDateTime.of(2020, 1, 1, 0, 0));
        String number = prefix + "o" + (++sequence);
        jdbc.update("insert into orders(number,status,user_id,address_book_id,order_time,pay_status,amount,remark,pack_amount) values (?,?,?,1,?,?,?,'test snapshot',3)",
                number, state, owner, time, state == 1 ? 0 : state == 6 ? 2 : 1, new BigDecimal(amount));
        return jdbc.queryForObject("select id from orders where number=?", Long.class, number);
    }

    private void detail(long order, String name, int quantity, boolean meal) {
        jdbc.update("insert into order_detail(order_id,name,dish_id,setmeal_id,dish_flavor,number,amount) values (?,?,?,?,?,?,1.00)",
                order, name, meal ? null : 100L, meal ? 100L : null, meal ? null : "微辣", quantity);
    }

    private static void moneySeries(String series, String... expected) {
        String[] actual = series.split(","); assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(0, new BigDecimal(expected[i]).compareTo(new BigDecimal(actual[i])));
        }
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> rows = new TreeMap<>();
        for (String table : jdbc.queryForList("show tables", String.class)) {
            assertTrue(table.matches("[a-z_]+"));
            rows.put(table, jdbc.queryForList("select * from `" + table + "` order by id"));
        }
        return rows;
    }
}
