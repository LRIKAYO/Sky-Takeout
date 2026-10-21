package com.sky.controller.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.sky.entity.Dish;
import com.sky.properties.JwtProperties;
import com.sky.service.DishService;
import com.sky.service.SetmealService;
import com.sky.utils.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 显式运行；真实 HTTP、MySQL、Redis。Redis 必须使用独立验收实例。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Day07AcceptanceIT {
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtProperties jwt;
    @Autowired private RedisTemplate<String, Object> redis;
    @Autowired private CacheManager cacheManager;
    @SpyBean private DishService dishes;
    @SpyBean private SetmealService meals;

    private final String prefix = "d7" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    private Map<String, List<Map<String, Object>>> original;
    private HttpHeaders admin;
    private HttpHeaders userA;
    private HttpHeaders userB;
    private long a;
    private long b;

    @BeforeEach
    void prepare() {
        assertEquals("sky_take_out_course", jdbc.queryForObject("select database()", String.class));
        // 清缓存会涉及整个命名空间，因此禁止对日常使用的 Redis 端口执行此验收。
        assertEquals("16379", System.getProperty("spring.redis.port"), "请启动独立验收 Redis 并显式指定端口 16379");
        assertTrue(cacheManager instanceof RedisCacheManager);
        original = snapshot();
        a = user(prefix + "a");
        b = user(prefix + "b");
        userA = token(false, a);
        userB = token(false, b);
        Long employeeId = jdbc.queryForObject("select min(id) from employee where status=1", Long.class);
        assertNotNull(employeeId);
        admin = token(true, employeeId);
    }

    @AfterEach
    void cleanupAndVerifyOriginalRows() {
        if (original == null) return;
        String pattern = prefix + "%";
        jdbc.update("delete from shopping_cart where user_id in (select id from user where openid like ?)", pattern);
        jdbc.update("delete from user where openid like ?", pattern);
        jdbc.update("delete from setmeal_dish where setmeal_id in (select id from setmeal where name like ?)", pattern);
        jdbc.update("delete from setmeal where name like ?", pattern);
        jdbc.update("delete from dish_flavor where dish_id in (select id from dish where name like ?)", pattern);
        jdbc.update("delete from dish where name like ?", pattern);
        jdbc.update("delete from category where name like ?", pattern);
        assertEquals(original, snapshot(), "验收前后所有业务表行内容应相同（不比较自增计数）");
    }

    @Test
    void cachesAndEvictsDishAndSetmealListsThroughRealHttp() {
        long c1 = category("c1", 1);
        long c2 = category("c2", 1);
        long m1 = category("m1", 2);
        long m2 = category("m2", 2);
        redis.opsForValue().set("SHOP_STATUS", 1);
        assertEquals(1, raw(HttpMethod.GET, "/user/shop/status", null, new HttpHeaders()).getBody().path("data").asInt());
        assertEquals(HttpStatus.UNAUTHORIZED,
                raw(HttpMethod.GET, "/admin/shop/status", null, new HttpHeaders()).getStatusCode());
        call(HttpMethod.PUT, "/admin/shop/0", null, admin, 1);
        assertEquals(0, call(HttpMethod.GET, "/admin/shop/status", null, admin, 1).path("data").asInt());
        assertEquals(0, raw(HttpMethod.GET, "/user/shop/status", null, new HttpHeaders()).getBody().path("data").asInt());
        call(HttpMethod.PUT, "/admin/shop/1", null, admin, 1);
        call(HttpMethod.PUT, "/admin/shop/2", null, admin, 0);

        String dishUrl = "/user/dish/list?categoryId=" + c1;
        clearInvocations(dishes);
        assertEquals(0, get(dishUrl).size());
        assertEquals(0, get(dishUrl).size());
        verify(dishes, times(1)).listWithFlavor(any(Dish.class));
        assertNotNull(redis.opsForValue().get("dish_" + c1));

        Map<String, Object> dish = dishBody("dish", c1);
        call(HttpMethod.POST, "/admin/dish", dish, admin, 1);
        long dishId = id("dish", (String) dish.get("name"));
        assertFalse(redis.hasKey("dish_" + c1));
        clearInvocations(dishes);
        JsonNode first = get(dishUrl);
        assertEquals(1, first.size());
        assertEquals("spice", first.get(0).path("flavors").get(0).path("name").asText());
        assertEquals(first, get(dishUrl));
        verify(dishes, times(1)).listWithFlavor(any(Dish.class));
        assertEquals(HttpStatus.UNAUTHORIZED, raw(HttpMethod.GET, dishUrl, null, new HttpHeaders()).getStatusCode());

        get("/user/dish/list?categoryId=" + c2);
        dish.put("id", dishId);
        dish.put("categoryId", c2);
        dish.put("price", new BigDecimal("9.75"));
        call(HttpMethod.PUT, "/admin/dish", dish, admin, 1);
        assertFalse(redis.hasKey("dish_" + c1));
        assertFalse(redis.hasKey("dish_" + c2));
        assertEquals(0, get(dishUrl).size());
        JsonNode moved = get("/user/dish/list?categoryId=" + c2);
        assertEquals(dishId, moved.get(0).path("id").asLong());
        assertEquals(9.75, moved.get(0).path("price").asDouble());

        String mealUrl = "/user/setmeal/list?categoryId=" + m1;
        assertEquals(0, get(mealUrl).size());
        Map<String, Object> meal = mealBody("meal", m1, dishId);
        call(HttpMethod.POST, "/admin/setmeal", meal, admin, 1);
        long mealId = id("setmeal", (String) meal.get("name"));
        assertFalse(redis.hasKey("setmealCache::" + m1));
        call(HttpMethod.POST, "/admin/setmeal/status/1?id=" + mealId, null, admin, 1);
        clearInvocations(meals);
        assertEquals(mealId, get(mealUrl).get(0).path("id").asLong());
        get(mealUrl);
        verify(meals, times(1)).list(any());
        assertNotNull(redis.opsForValue().get("setmealCache::" + m1));

        get("/user/setmeal/list?categoryId=" + m2);
        meal.put("id", mealId);
        meal.put("categoryId", m2);
        meal.put("price", new BigDecimal("22.25"));
        call(HttpMethod.PUT, "/admin/setmeal", meal, admin, 1);
        assertFalse(redis.hasKey("setmealCache::" + m1));
        assertFalse(redis.hasKey("setmealCache::" + m2));
        assertEquals(0, get(mealUrl).size());
        String movedMealUrl = "/user/setmeal/list?categoryId=" + m2;
        assertEquals(22.25, get(movedMealUrl).get(0).path("price").asDouble());
        assertEquals(1, get("/user/setmeal/dish/" + mealId).size());

        call(HttpMethod.POST, "/admin/dish/status/0?id=" + dishId, null, admin, 1);
        assertFalse(redis.hasKey("dish_" + c2));
        assertFalse(redis.hasKey("setmealCache::" + m2));
        assertEquals(0, jdbc.queryForObject("select status from setmeal where id=?", Integer.class, mealId));
        assertEquals(0, get("/user/dish/list?categoryId=" + c2).size());
        assertEquals(0, get(movedMealUrl).size());
        call(HttpMethod.GET, "/user/setmeal/dish/" + mealId, null, userA, 0);

        call(HttpMethod.POST, "/admin/dish/status/1?id=" + dishId, null, admin, 1);
        assertEquals(1, get("/user/dish/list?categoryId=" + c2).size());
        assertEquals(0, get(movedMealUrl).size());
        call(HttpMethod.POST, "/admin/setmeal/status/1?id=" + mealId, null, admin, 1);
        assertEquals(1, get(movedMealUrl).size());
        call(HttpMethod.POST, "/admin/setmeal/status/0?id=" + mealId, null, admin, 1);
        assertFalse(redis.hasKey("setmealCache::" + m2));
        get(movedMealUrl);
        call(HttpMethod.DELETE, "/admin/setmeal?ids=" + mealId, null, admin, 1);
        assertFalse(redis.hasKey("setmealCache::" + m2));
        assertEquals(0, get(movedMealUrl).size());

        call(HttpMethod.POST, "/admin/dish/status/2?id=" + dishId, null, admin, 0);
        assertTrue(redis.hasKey("dish_" + c2), "失败请求不应清除已有缓存");
        call(HttpMethod.POST, "/admin/dish/status/0?id=" + dishId, null, admin, 1);
        get("/user/dish/list?categoryId=" + c2);
        call(HttpMethod.DELETE, "/admin/dish?ids=" + dishId, null, admin, 1);
        assertFalse(redis.hasKey("dish_" + c2));
        assertEquals(0, get("/user/dish/list?categoryId=" + c2).size());
        call(HttpMethod.POST, "/admin/dish/status/1?id=9223372036854775807", null, admin, 0);
        for (String path : List.of("/user/dish/list", "/user/setmeal/list")) {
            assertEquals(HttpStatus.BAD_REQUEST, raw(HttpMethod.GET, path, null, userA).getStatusCode());
            assertEquals(HttpStatus.BAD_REQUEST, raw(HttpMethod.GET, path + "?categoryId=abc", null, userA).getStatusCode());
            call(HttpMethod.GET, path + "?categoryId=-1", null, userA, 0);
        }
        assertEquals(1, redis.opsForValue().get("SHOP_STATUS"));
    }

    @Test
    void cartUsesServerPricesExactFlavorsAndCurrentUserOnly() {
        long c = category("c", 1);
        long mc = category("mc", 2);
        Map<String, Object> dish = dishBody("cartdish", c);
        call(HttpMethod.POST, "/admin/dish", dish, admin, 1);
        long d = id("dish", (String) dish.get("name"));
        Map<String, Object> meal = mealBody("cartmeal", mc, d);
        call(HttpMethod.POST, "/admin/setmeal", meal, admin, 1);
        long m = id("setmeal", (String) meal.get("name"));
        call(HttpMethod.POST, "/admin/setmeal/status/1?id=" + m, null, admin, 1);

        String add = "/user/shoppingCart/add";
        String list = "/user/shoppingCart/list";
        String clean = "/user/shoppingCart/clean";
        assertEquals(0, get(list).size());
        call(HttpMethod.POST, add, Map.of("dishId", d, "dishFlavor", "mild"), userA, 1);
        // 先加有口味再加无口味，能发现“空口味条件被动态 SQL 忽略”的回归。
        call(HttpMethod.POST, add, Map.of("dishId", d, "userId", b, "amount", 0, "number", 99), userA, 1);
        call(HttpMethod.POST, add, Map.of("dishId", d, "dishFlavor", ""), userA, 1);
        call(HttpMethod.POST, add, Map.of("dishId", d, "dishFlavor", "   "), userA, 1);
        call(HttpMethod.POST, add, Map.of("dishId", d, "dishFlavor", " hot "), userA, 1);
        call(HttpMethod.POST, add, Map.of("dishId", d, "dishFlavor", "hot"), userA, 1);
        call(HttpMethod.POST, add, Map.of("setmealId", m), userA, 1);
        call(HttpMethod.POST, add, Map.of("setmealId", m), userA, 1);
        JsonNode rows = get(list);
        assertEquals(4, rows.size());
        for (JsonNode row : rows) {
            assertEquals(a, row.path("userId").asLong());
            assertFalse(row.path("createTime").asText().isBlank());
            if (!row.path("setmealId").isNull()) {
                assertEquals(m, row.path("setmealId").asLong());
                assertTrue(row.path("dishId").isNull());
                assertEquals(2, row.path("number").asInt());
                assertEquals(20.0, row.path("amount").asDouble());
            } else {
                assertEquals(d, row.path("dishId").asLong());
                assertEquals(6.5, row.path("amount").asDouble());
                String f = row.path("dishFlavor").asText();
                assertEquals(f.isEmpty() ? 3 : f.equals("mild") ? 1 : 2, row.path("number").asInt());
            }
        }
        assertEquals(4, jdbc.queryForObject("select count(*) from shopping_cart where user_id=?", Integer.class, a));
        call(HttpMethod.POST, add, Map.of("dishId", d), userB, 1);
        assertEquals(1, call(HttpMethod.GET, list + "?userId=" + a, null, userB, 1).path("data").size());
        assertEquals(b, call(HttpMethod.GET, list, null, userB, 1).path("data").get(0).path("userId").asLong());

        long sum = jdbc.queryForObject("select sum(number) from shopping_cart where user_id=?", Long.class, a);
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(Map.of(),
                Map.of("dishId", d, "setmealId", m), Map.of("dishId", 0),
                Map.of("setmealId", -1), Map.of("dishId", Long.MAX_VALUE),
                Map.of("setmealId", m, "dishFlavor", "hot"),
                Map.of("dishId", d, "dishFlavor", "x".repeat(51)))) {
            call(HttpMethod.POST, add, invalid, userA, 0);
        }
        call(HttpMethod.POST, "/admin/dish/status/0?id=" + d, null, admin, 1);
        call(HttpMethod.POST, add, Map.of("dishId", d), userA, 0);
        call(HttpMethod.POST, add, Map.of("setmealId", m), userA, 0);
        assertEquals(sum, jdbc.queryForObject("select sum(number) from shopping_cart where user_id=?", Long.class, a));

        for (HttpHeaders bad : List.of(new HttpHeaders(), token(true, 1), expiredUserToken(a))) {
            assertEquals(HttpStatus.UNAUTHORIZED, raw(HttpMethod.POST, add, Map.of("dishId", d), bad).getStatusCode());
            assertEquals(HttpStatus.UNAUTHORIZED, raw(HttpMethod.GET, list, null, bad).getStatusCode());
            assertEquals(HttpStatus.UNAUTHORIZED, raw(HttpMethod.DELETE, clean, null, bad).getStatusCode());
            assertEquals(HttpStatus.UNAUTHORIZED, raw(HttpMethod.POST, "/user/shoppingCart/sub", Map.of("dishId", d), bad).getStatusCode());
        }
        String sub = "/user/shoppingCart/sub";
        // Stop-state items remain removable; flavor and user filters apply to decrement too.
        call(HttpMethod.POST, sub, Map.of("dishId", d, "userId", b), userA, 1);
        assertEquals(2, jdbc.queryForObject("select number from shopping_cart where user_id=? and dish_id=? and dish_flavor=''", Integer.class, a, d));
        assertEquals(1, jdbc.queryForObject("select number from shopping_cart where user_id=? and dish_id=?", Integer.class, b, d));
        call(HttpMethod.POST, sub, Map.of("dishId", d, "dishFlavor", "mild"), userA, 1);
        assertEquals(0, jdbc.queryForObject("select count(*) from shopping_cart where user_id=? and dish_flavor='mild'", Integer.class, a));
        assertEquals(2, jdbc.queryForObject("select number from shopping_cart where user_id=? and dish_flavor='hot'", Integer.class, a));
        call(HttpMethod.POST, sub, Map.of("setmealId", m), userA, 1);
        call(HttpMethod.POST, sub, Map.of("setmealId", m), userA, 1);
        call(HttpMethod.POST, sub, Map.of("setmealId", m), userA, 1);
        assertEquals(0, jdbc.queryForObject("select count(*) from shopping_cart where user_id=? and setmeal_id=?", Integer.class, a, m));
        call(HttpMethod.POST, sub, Map.of("dishId", d, "setmealId", m), userA, 0);
        call(HttpMethod.POST, sub, Map.of("dishId", 0), userA, 0);
        call(HttpMethod.DELETE, clean + "?userId=" + b, null, userA, 1);
        call(HttpMethod.DELETE, clean, null, userA, 1);
        assertEquals(0, get(list).size());
        assertEquals(1, call(HttpMethod.GET, list, null, userB, 1).path("data").size());
        assertEquals(1, jdbc.queryForObject("select count(*) from shopping_cart where user_id=?", Integer.class, b));
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("show tables", String.class)) {
            result.put(table, jdbc.queryForList("select * from `" + table + "` order by id"));
        }
        return result;
    }

    private long category(String suffix, int type) {
        String name = prefix + suffix;
        jdbc.update("insert into category(name,type,status,sort,create_time) values (?,?,1,1,now())", name, type);
        return id("category", name);
    }

    private long user(String openid) {
        jdbc.update("insert into user(openid,create_time) values (?,now())", openid);
        return jdbc.queryForObject("select id from user where openid=?", Long.class, openid);
    }

    private long id(String table, String name) {
        return jdbc.queryForObject("select id from " + table + " where name=?", Long.class, name);
    }

    private Map<String, Object> dishBody(String suffix, long category) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", prefix + suffix);
        body.put("categoryId", category);
        body.put("price", new BigDecimal("6.50"));
        body.put("image", "http://localhost:8080/uploads/test.png");
        body.put("status", 1);
        body.put("flavors", List.of(Map.of("name", "spice", "value", "[\"mild\",\"hot\"]")));
        return body;
    }

    private Map<String, Object> mealBody(String suffix, long category, long dish) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", prefix + suffix);
        body.put("categoryId", category);
        body.put("price", new BigDecimal("20.00"));
        body.put("image", "http://localhost:8080/uploads/test.png");
        body.put("setmealDishes", List.of(Map.of("dishId", dish, "copies", 1)));
        return body;
    }

    private HttpHeaders token(boolean isAdmin, long id) {
        HttpHeaders h = new HttpHeaders();
        h.set(isAdmin ? jwt.getAdminTokenName() : jwt.getUserTokenName(),
                JwtUtil.createJWT(isAdmin ? jwt.getAdminSecretKey() : jwt.getUserSecretKey(),
                        isAdmin ? jwt.getAdminTtl() : jwt.getUserTtl(),
                        new HashMap<>(Map.of(isAdmin ? "empId" : "userId", id))));
        return h;
    }

    private HttpHeaders expiredUserToken(long id) {
        HttpHeaders h = new HttpHeaders();
        h.set(jwt.getUserTokenName(), JwtUtil.createJWT(jwt.getUserSecretKey(), -1000,
                new HashMap<>(Map.of("userId", id))));
        return h;
    }

    private JsonNode get(String path) {
        return call(HttpMethod.GET, path, null, userA, 1).path("data");
    }

    private ResponseEntity<JsonNode> raw(HttpMethod method, String path, Object body, HttpHeaders headers) {
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private JsonNode call(HttpMethod method, String path, Object body, HttpHeaders headers, int code) {
        ResponseEntity<JsonNode> response = raw(method, path, body, headers);
        assertEquals(HttpStatus.OK, response.getStatusCode(), path);
        assertNotNull(response.getBody(), path);
        assertEquals(code, response.getBody().path("code").asInt(), path + ": " + response.getBody());
        return response.getBody();
    }
}
