package com.sky.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.sky.exception.BaseException;
import com.sky.properties.JwtProperties;
import com.sky.service.WorkspaceService;
import com.sky.task.OrderTask;
import com.sky.utils.JwtUtil;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real login/HTTP/JWT/MySQL/POI; writes only this run's owned empty schema. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Day12AcceptanceIT {
    private static final String DATABASE = System.getProperty("day12.test.database", "");
    private static final List<String> PATHS = List.of("/admin/workspace/businessData", "/admin/workspace/overviewOrders",
            "/admin/workspace/overviewDishes", "/admin/workspace/overviewSetmeals", "/admin/report/export");
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtProperties jwt;
    @Autowired private WorkspaceService workspace;
    @Autowired private ApplicationContext context;
    private final String prefix = "d12" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    private HttpHeaders admin;
    private long owner, employee;
    private int sequence;
    private boolean owned;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        if (!DATABASE.matches("day12_verify_[a-f0-9]{12}")) throw new IllegalStateException("Run tools/verify_day12.ps1");
        String url = System.getenv("DAY12_TEST_JDBC_URL");
        if (url == null || !url.contains("/" + DATABASE + "?")) throw new IllegalStateException("Owned schema and URL must match");
        registry.add("spring.datasource.druid.url", () -> url);
    }

    @BeforeEach
    void prepare() {
        assertEquals(DATABASE, jdbc.queryForObject("select database()", String.class));
        assertTrue(context.getBeansOfType(OrderTask.class).isEmpty());
        for (String table : List.of("employee", "user", "orders", "order_detail", "dish", "setmeal")) {
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class));
        }
        owned = true;
        String password = "day12-local-test-only";
        jdbc.update("insert into employee(name,username,password,phone,sex,id_number,status) values (?,?,?,'13900000000','1','000000000000000000',1)",
                prefix, prefix, DigestUtils.md5DigestAsHex(password.getBytes(StandardCharsets.UTF_8)));
        employee = jdbc.queryForObject("select id from employee where username=?", Long.class, prefix);
        ResponseEntity<JsonNode> login = http.postForEntity("/admin/employee/login",
                Map.of("username", prefix, "password", password), JsonNode.class);
        assertEquals(HttpStatus.OK, login.getStatusCode()); assertNotNull(login.getBody());
        assertEquals(1, login.getBody().path("code").asInt());
        String token = login.getBody().path("data").path("token").asText(); assertFalse(token.isBlank());
        admin = new HttpHeaders(); admin.set(jwt.getAdminTokenName(), token);
    }

    @AfterEach
    void cleanup() {
        if (!owned) return;
        assertTrue(DATABASE.matches("day12_verify_[a-f0-9]{12}"));
        assertEquals(DATABASE, jdbc.queryForObject("select database()", String.class));
        for (String table : List.of("order_detail", "orders", "dish", "setmeal", "user", "employee")) {
            jdbc.update("delete from " + table);
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class));
        }
    }

    @Test
    void todayMetricsAndOrderOverviewHonorBothMidnightBoundsAndAllStates() {
        user(today.atStartOfDay()); user(today.plusDays(1).atStartOfDay().minusSeconds(1));
        user(today.atStartOfDay().minusSeconds(1)); user(today.plusDays(1).atStartOfDay());
        for (int state = 1; state <= 6; state++) order(state, state + ".00", today.atTime(12, 0));
        order(5, "999.00", today.atStartOfDay().minusSeconds(1));
        order(5, "999.00", today.plusDays(1).atStartOfDay());
        JsonNode data = json("/admin/workspace/businessData");
        assertEquals(5.0, data.path("turnover").asDouble());
        assertEquals(1, data.path("validOrderCount").asInt());
        assertEquals(1.0 / 6, data.path("orderCompletionRate").asDouble(), 0.000001);
        assertEquals(5.0, data.path("unitPrice").asDouble()); assertEquals(2, data.path("newUsers").asInt());
        JsonNode overview = json("/admin/workspace/overviewOrders");
        for (String field : List.of("waitingOrders", "deliveredOrders", "completedOrders", "cancelledOrders")) {
            assertEquals(1, overview.path(field).asInt(), field);
        }
        assertEquals(6, overview.path("allOrders").asInt());
    }

    @Test
    void currentDishAndSetmealCountsIncludeBothStatusesRegardlessOfAge() {
        for (int i = 0; i < 5; i++) jdbc.update("insert into dish(name,category_id,price,status,image) values (?,1,6.50,?,'test.png')", prefix + i, i < 3 ? 1 : 0);
        for (int i = 0; i < 3; i++) jdbc.update("insert into setmeal(name,category_id,price,status,image) values (?,1,20.00,?,'test.png')", prefix + i, i < 2 ? 1 : 0);
        JsonNode dish = json("/admin/workspace/overviewDishes");
        assertEquals(3, dish.path("sold").asInt()); assertEquals(2, dish.path("discontinued").asInt());
        JsonNode meal = json("/admin/workspace/overviewSetmeals");
        assertEquals(2, meal.path("sold").asInt()); assertEquals(1, meal.path("discontinued").asInt());
    }

    @Test
    void emptyDatabaseExportsThirtyContinuousDaysWithNumericZeros() throws Exception {
        try (XSSFWorkbook book = book(download())) {
            XSSFSheet sheet = book.getSheet("Sheet1");
            assertEquals(0.0, number(sheet, 3, 2)); assertEquals(0.0, number(sheet, 3, 4));
            assertEquals(0.0, number(sheet, 4, 4));
            for (int i = 0; i < 30; i++) {
                assertEquals(today.minusDays(30).plusDays(i).toString(), sheet.getRow(7 + i).getCell(1).getStringCellValue());
                for (int col = 2; col <= 6; col++) assertEquals(0.0, number(sheet, 7 + i, col));
            }
        }
    }

    @Test
    void knownThirtyDayFixturesExportCorrectSummaryDailyValuesAndDownloadableFile() throws Exception {
        LocalDate start = today.minusDays(30);
        user(start.minusDays(1).atStartOfDay());
        for (int i = 0; i < 30; i++) {
            LocalDate date = start.plusDays(i);
            order(5, (i + 1) + ".10", date.atStartOfDay());
            order(5, (i + 1) + ".20", date.plusDays(1).atStartOfDay().minusSeconds(1));
            order(2, "999.00", date.atTime(13, 0)); order(6, "9999.00", date.atTime(14, 0));
            for (int n = 0; n < i % 3; n++) user(date.atTime(12, n));
        }
        order(5, "99999.00", start.atStartOfDay().minusSeconds(1));
        order(5, "99999.00", today.atStartOfDay());
        user(start.atStartOfDay().minusSeconds(1)); user(today.atStartOfDay());
        byte[] bytes = download();
        try (XSSFWorkbook book = book(bytes)) {
            XSSFSheet sheet = book.getSheet("Sheet1");
            assertTrue(sheet.getRow(0).getCell(1).getStringCellValue().contains("教学测试"));
            assertEquals(start + "至" + today.minusDays(1), sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals(939.0, number(sheet, 3, 2), 0.000001);
            assertEquals(0.5, number(sheet, 3, 4)); assertEquals(30.0, number(sheet, 3, 6));
            assertEquals(60.0, number(sheet, 4, 2)); assertEquals(15.65, number(sheet, 4, 4), 0.000001);
            assertEquals("50.00%", new DataFormatter(Locale.US).formatCellValue(sheet.getRow(3).getCell(4)));
            for (int i = 0; i < 30; i++) {
                assertEquals(start.plusDays(i).toString(), sheet.getRow(7 + i).getCell(1).getStringCellValue());
                assertEquals(2 * (i + 1) + 0.30, number(sheet, 7 + i, 2), 0.000001);
                assertEquals(2.0, number(sheet, 7 + i, 3)); assertEquals(0.5, number(sheet, 7 + i, 4));
                assertEquals((i + 1) + 0.15, number(sheet, 7 + i, 5), 0.000001);
                assertEquals((double) (i % 3), number(sheet, 7 + i, 6));
                assertEquals(CellType.NUMERIC, sheet.getRow(7 + i).getCell(4).getCellTypeEnum());
            }
        }
        String output = System.getenv("DAY12_TEST_EXPORT_PATH");
        if (output != null && !output.isBlank()) {
            Path destination = Path.of(output).toAbsolutePath().normalize();
            assertTrue(destination.startsWith(Path.of("D:/spring_poject/outputs/day12-excel-20261027").toAbsolutePath().normalize()));
            Files.write(destination, bytes);
        }
    }

    @Test
    void periodRatiosAndUnitPricesAreCalculatedFromTotalsRatherThanDailyAverages() throws Exception {
        LocalDate first = today.minusDays(30);
        order(5, "10.00", first.atTime(12, 0)); order(2, "999.00", first.atTime(13, 0));
        for (int i = 0; i < 3; i++) order(5, "20.00", first.plusDays(1).atTime(12, i));
        for (int i = 0; i < 5; i++) order(6, "999.00", first.plusDays(1).atTime(13, i));
        try (XSSFWorkbook book = book(download())) {
            XSSFSheet sheet = book.getSheet("Sheet1");
            assertEquals(70.0, number(sheet, 3, 2)); assertEquals(4.0, number(sheet, 4, 2));
            assertEquals(0.4, number(sheet, 3, 4), 0.000001); assertEquals(17.5, number(sheet, 4, 4));
            assertEquals(0.5, number(sheet, 7, 4)); assertEquals(0.375, number(sheet, 8, 4));
            assertEquals(10.0, number(sheet, 7, 5)); assertEquals(20.0, number(sheet, 8, 5));
        }
    }

    @Test
    void existingDay11StatisticsStillAgreeWithTheExportAggregation() throws Exception {
        LocalDate date = today.minusDays(1);
        order(5, "0.10", date.atStartOfDay()); order(5, "0.20", date.atTime(12, 0));
        order(6, "999.00", date.atTime(13, 0));
        String query = "?begin=" + date + "&end=" + date;
        assertEquals(0, new BigDecimal("0.30").compareTo(new BigDecimal(json("/admin/report/turnoverStatistics" + query).path("turnoverList").asText())));
        JsonNode count = json("/admin/report/ordersStatistics" + query);
        assertEquals(3, count.path("totalOrderCount").asInt()); assertEquals(2, count.path("validOrderCount").asInt());
        try (XSSFWorkbook book = book(download())) {
            assertEquals(0.30, number(book.getSheet("Sheet1"), 3, 2), 0.000001);
            assertEquals(2.0 / 3, number(book.getSheet("Sheet1"), 3, 4), 0.000001);
        }
    }

    @Test
    void fiveRoutesRequireValidAdminJwtIncludingTheBinaryDownload() {
        String user = JwtUtil.createJWT(jwt.getUserSecretKey(), jwt.getUserTtl(), new HashMap<>(Map.of("userId", 1L)));
        String expired = JwtUtil.createJWT(jwt.getAdminSecretKey(), -60000, new HashMap<>(Map.of("empId", employee)));
        for (String path : PATHS) {
            assertEquals(HttpStatus.UNAUTHORIZED, http.exchange(path, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), byte[].class).getStatusCode());
            for (String token : List.of(user, expired, "invalid-token")) {
                HttpHeaders invalid = new HttpHeaders(); invalid.set(jwt.getAdminTokenName(), token);
                assertEquals(HttpStatus.UNAUTHORIZED, http.exchange(path, HttpMethod.GET, new HttpEntity<>(invalid), byte[].class).getStatusCode());
            }
            assertEquals(HttpStatus.OK, http.exchange(path, HttpMethod.GET, new HttpEntity<>(admin), byte[].class).getStatusCode());
        }
    }

    @Test
    void reportReadsPreserveAllRowsAndTheTemplateBytes() throws Exception {
        order(5, "10.00", today.atTime(12, 0));
        Map<String, List<Map<String, Object>>> before = snapshot(); byte[] template = template();
        for (String path : PATHS) assertEquals(HttpStatus.OK, http.exchange(path, HttpMethod.GET, new HttpEntity<>(admin), byte[].class).getStatusCode());
        assertEquals(before, snapshot()); assertArrayEquals(template, template());
    }

    @Test
    void invalidBusinessIntervalsFailWithoutChangingAnyRows() {
        Map<String, List<Map<String, Object>>> before = snapshot(); LocalDateTime start = today.atStartOfDay();
        assertThrows(BaseException.class, () -> workspace.getBusinessData(null, start));
        assertThrows(BaseException.class, () -> workspace.getBusinessData(start, null));
        assertThrows(BaseException.class, () -> workspace.getBusinessData(start, start));
        assertThrows(BaseException.class, () -> workspace.getBusinessData(start.plusDays(1), start));
        assertEquals(before, snapshot());
    }

    private byte[] download() {
        ResponseEntity<byte[]> response = http.exchange("/admin/report/export", HttpMethod.GET, new HttpEntity<>(admin), byte[].class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getHeaders().getContentType());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getFirst("Content-Disposition").contains(".xlsx"));
        byte[] body = response.getBody(); assertNotNull(body); assertTrue(body.length > 1000);
        assertEquals(body.length, response.getHeaders().getContentLength());
        assertEquals('P', body[0]); assertEquals('K', body[1]); return body;
    }

    private XSSFWorkbook book(byte[] bytes) throws Exception { return new XSSFWorkbook(new ByteArrayInputStream(bytes)); }
    private static double number(XSSFSheet sheet, int row, int col) { return sheet.getRow(row).getCell(col).getNumericCellValue(); }
    private JsonNode json(String path) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(admin), JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode()); assertNotNull(response.getBody());
        assertEquals(1, response.getBody().path("code").asInt(), String.valueOf(response.getBody()));
        return response.getBody().path("data");
    }
    private long user(LocalDateTime time) {
        String openid = prefix + "u" + (++sequence); jdbc.update("insert into user(openid,create_time) values (?,?)", openid, time);
        long id = jdbc.queryForObject("select id from user where openid=?", Long.class, openid); if (owner == 0) owner = id; return id;
    }
    private void order(int state, String amount, LocalDateTime time) {
        if (owner == 0) user(LocalDateTime.of(2020, 1, 1, 0, 0));
        jdbc.update("insert into orders(number,status,user_id,address_book_id,order_time,pay_status,amount) values (?,?,?,1,?,?,?)",
                prefix + "o" + (++sequence), state, owner, time, state == 1 ? 0 : state == 6 ? 2 : 1, new BigDecimal(amount));
    }
    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> rows = new TreeMap<>();
        for (String table : jdbc.queryForList("show tables", String.class)) {
            assertTrue(table.matches("[a-z_]+")); rows.put(table, jdbc.queryForList("select * from `" + table + "` order by id"));
        }
        return rows;
    }
    private byte[] template() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/template/运营数据报表模板.xlsx")) {
            assertNotNull(input); return input.readAllBytes();
        }
    }
}
