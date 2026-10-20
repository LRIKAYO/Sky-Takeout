package com.sky.controller.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 显式运行：真实 HTTP、Service、MyBatis、MySQL；仅创建并清理自己的临时数据。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Day06BrowseAcceptanceIT {
    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtProperties jwt;

    private final HttpHeaders headers = new HttpHeaders();

    @Test
    void verifiesAllBrowseEndpointsAgainstRealDatabase() {
        assertEquals("sky_take_out_course", jdbc.queryForObject("select database()", String.class));
        String prefix = "d6b" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String pattern = prefix + "%";
        try {
            for (String path : List.of("/user/category/list", "/user/dish/list?categoryId=1",
                    "/user/setmeal/list?categoryId=1", "/user/setmeal/dish/1")) {
                assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity(path, String.class).getStatusCode());
            }
            headers.set(jwt.getUserTokenName(), JwtUtil.createJWT(jwt.getUserSecretKey(), jwt.getUserTtl(),
                    new HashMap<>(Map.of("userId", 42L))));

            long dishCategory = category(prefix + "-c1", 1, 1);
            long mealCategory = category(prefix + "-c2", 2, 1);
            long disabledCategory = category(prefix + "-cx", 1, 0);
            long on = dish(prefix + "-on", dishCategory, 1);
            long plain = dish(prefix + "-plain", dishCategory, 1);
            long off = dish(prefix + "-off", dishCategory, 0);
            dish(prefix + "-other", disabledCategory, 1);
            jdbc.update("insert into dish_flavor(dish_id,name,value) values (?,?,?)",
                    on, "spice", "[\"mild\",\"hot\"]");
            long meal = meal(prefix + "-m1", mealCategory, 1);
            long stoppedMeal = meal(prefix + "-m0", mealCategory, 0);
            relation(meal, on, prefix + "-on", 2);
            relation(meal, plain, prefix + "-plain", 3);

            JsonNode all = data("/user/category/list");
            assertTrue(ids(all).containsAll(List.of(dishCategory, mealCategory)));
            assertFalse(ids(all).contains(disabledCategory));
            JsonNode dishCategories = data("/user/category/list?type=1");
            assertTrue(ids(dishCategories).contains(dishCategory));
            assertFalse(ids(dishCategories).contains(mealCategory));
            assertTrue(ids(data("/user/category/list?type=2")).contains(mealCategory));
            for (JsonNode row : all) {
                assertEquals(1, row.path("status").asInt());
            }
            result("/user/category/list?type=3", HttpStatus.OK, 0);

            String dishUrl = "/user/dish/list?categoryId=" + dishCategory;
            JsonNode dishes = data(dishUrl + "&status=0");
            assertEquals(2, dishes.size());
            assertTrue(ids(dishes).containsAll(List.of(on, plain)));
            assertFalse(ids(dishes).contains(off));
            for (JsonNode row : dishes) {
                assertEquals(dishCategory, row.path("categoryId").asLong());
                assertEquals(1, row.path("status").asInt());
                assertEquals("http://localhost:8080/uploads/test.png", row.path("image").asText());
                if (row.path("id").asLong() == on) {
                    assertEquals(1, row.path("flavors").size());
                    assertEquals("spice", row.path("flavors").get(0).path("name").asText());
                    assertEquals("[\"mild\",\"hot\"]", row.path("flavors").get(0).path("value").asText());
                } else {
                    assertTrue(row.path("flavors").isArray());
                    assertEquals(0, row.path("flavors").size());
                }
            }
            assertEquals(0, data("/user/dish/list?categoryId=" + mealCategory).size());

            JsonNode meals = data("/user/setmeal/list?categoryId=" + mealCategory + "&status=0");
            assertEquals(List.of(meal), ids(meals));
            assertFalse(ids(meals).contains(stoppedMeal));
            assertEquals(1, meals.get(0).path("status").asInt());
            assertEquals(0, data("/user/setmeal/list?categoryId=" + dishCategory).size());
            JsonNode items = data("/user/setmeal/dish/" + meal);
            assertEquals(2, items.size());
            assertEquals(prefix + "-on", items.get(0).path("name").asText());
            assertEquals(2, items.get(0).path("copies").asInt());
            assertEquals(prefix + "-plain", items.get(1).path("name").asText());
            assertEquals(3, items.get(1).path("copies").asInt());
            for (JsonNode item : items) {
                assertEquals("http://localhost:8080/uploads/test.png", item.path("image").asText());
                assertEquals("browse fixture", item.path("description").asText());
            }
            result("/user/setmeal/dish/" + stoppedMeal, HttpStatus.OK, 0);
            result("/user/setmeal/dish/9223372036854775807", HttpStatus.OK, 0);
            result("/user/setmeal/dish/0", HttpStatus.OK, 0);
            for (String base : List.of("/user/dish/list", "/user/setmeal/list")) {
                result(base, HttpStatus.BAD_REQUEST, null);
                result(base + "?categoryId=abc", HttpStatus.BAD_REQUEST, null);
                result(base + "?categoryId=-1", HttpStatus.OK, 0);
            }
            String expired = JwtUtil.createJWT(jwt.getUserSecretKey(), -1000,
                    new HashMap<>(Map.of("userId", 42L)));
            headers.set(jwt.getUserTokenName(), expired);
            result("/user/category/list", HttpStatus.UNAUTHORIZED, null);
        } finally {
            jdbc.update("delete from setmeal_dish where setmeal_id in (select id from setmeal where name like ?)", pattern);
            jdbc.update("delete from setmeal where name like ?", pattern);
            jdbc.update("delete from dish_flavor where dish_id in (select id from dish where name like ?)", pattern);
            jdbc.update("delete from dish where name like ?", pattern);
            jdbc.update("delete from category where name like ?", pattern);
            for (String table : List.of("category", "dish", "setmeal")) {
                assertEquals(0, jdbc.queryForObject("select count(*) from " + table + " where name like ?",
                        Integer.class, pattern));
            }
        }
    }

    private JsonNode result(String path, HttpStatus status, Integer code) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertEquals(status, response.getStatusCode(), path);
        if (code != null) {
            assertNotNull(response.getBody(), path);
            assertEquals(code.intValue(), response.getBody().path("code").asInt(), path);
        }
        return response.getBody();
    }

    private JsonNode data(String path) {
        return result(path, HttpStatus.OK, 1).path("data");
    }

    private List<Long> ids(JsonNode rows) {
        List<Long> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.path("id").asLong()));
        return ids;
    }

    private long category(String name, int type, int status) {
        jdbc.update("insert into category(name,type,status,sort,create_time) values (?,?,?,?,now())", name, type, status, 1);
        return jdbc.queryForObject("select id from category where name=?", Long.class, name);
    }

    private long dish(String name, long category, int status) {
        jdbc.update("insert into dish(name,category_id,price,status,image,description) values (?,?,6.50,?,?,?)",
                name, category, status, "http://localhost:8080/uploads/test.png", "browse fixture");
        return jdbc.queryForObject("select id from dish where name=?", Long.class, name);
    }

    private long meal(String name, long category, int status) {
        jdbc.update("insert into setmeal(name,category_id,price,status) values (?,?,15.00,?)", name, category, status);
        return jdbc.queryForObject("select id from setmeal where name=?", Long.class, name);
    }

    private void relation(long meal, long dish, String name, int copies) {
        jdbc.update("insert into setmeal_dish(setmeal_id,dish_id,name,price,copies) values (?,?,?,6.50,?)",
                meal, dish, name, copies);
    }
}
