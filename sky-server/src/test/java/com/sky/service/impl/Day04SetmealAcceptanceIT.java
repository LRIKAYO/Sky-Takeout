package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.dto.SetmealDTO;
import com.sky.entity.SetmealDish;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.service.SetmealService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** 显式运行；仅操作唯一名称的临时菜品、套餐和关系，结束后清理。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "sky.upload.mode=local")
class Day04SetmealAcceptanceIT {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SetmealService service;

    @SpyBean
    private SetmealDishMapper relations;

    @SpyBean
    private SetmealMapper meals;

    private final HttpHeaders headers = new HttpHeaders();

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(HttpMethod method, String path, Object body, int code) {
        ResponseEntity<Map> response = http.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), path);
        assertNotNull(response.getBody());
        assertEquals(code, ((Number) response.getBody().get("code")).intValue(), path);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    @Test
    void verifiesHttpLifecycleAndRollsBackAnInterruptedRelationReplacement() {
        assertEquals("sky_take_out_course", jdbc.queryForObject("select database()", String.class));
        String jdbcUrl = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                connection -> connection.getMetaData().getURL());
        assertTrue(jdbcUrl.startsWith("jdbc:mysql://localhost:")
                || jdbcUrl.startsWith("jdbc:mysql://127.0.0.1:"));
        String prefix = "d04_" + UUID.randomUUID().toString().substring(0, 12);
        Long dishCategory = jdbc.queryForObject("select min(id) from category where type=1 and status=1", Long.class);
        Long category = jdbc.queryForObject("select min(id) from category where type=2 and status=1", Long.class);
        assertNotNull(dishCategory);
        assertNotNull(category);
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity("/admin/setmeal/page?page=1&pageSize=10", Map.class).getStatusCode());
        Map<String, Object> login = http.postForObject("/admin/employee/login",
                Map.of("username", "admin", "password", "123456"), Map.class);
        assertEquals(1, ((Number) login.get("code")).intValue());
        Map<String, Object> account = (Map<String, Object>) login.get("data");
        headers.set("token", (String) account.get("token"));
        long employeeId = ((Number) account.get("id")).longValue();
        try {
            jdbc.update("insert into dish(name,category_id,price,image,status) values (?,?,6.50,'',1)", prefix + "_dish", dishCategory);
            jdbc.update("insert into dish(name,category_id,price,image,status) values (?,?,7.00,'',0)", prefix + "_stopped", dishCategory);
            Long dishId = jdbc.queryForObject("select id from dish where name=?", Long.class, prefix + "_dish");
            Long stoppedId = jdbc.queryForObject("select id from dish where name=?", Long.class, prefix + "_stopped");

            List<Map<String, Object>> dishes = (List<Map<String, Object>>) request(HttpMethod.GET,
                    "/admin/dish/list?categoryId=" + dishCategory, null, 1).get("data");
            assertTrue(dishes.stream().anyMatch(d -> ((Number) d.get("id")).longValue() == dishId));
            assertFalse(dishes.stream().anyMatch(d -> ((Number) d.get("id")).longValue() == stoppedId));
            System.out.println("PASS: category dish selection excludes stopped dishes");

            SetmealDTO dto = new SetmealDTO();
            dto.setId(999999L);
            dto.setName(prefix + "_meal");
            dto.setCategoryId(category);
            dto.setPrice(new BigDecimal("9.90"));
            dto.setImage("http://localhost:8080/uploads/reference.png");
            dto.setStatus(1);
            dto.setSetmealDishes(List.of(SetmealDish.builder().id(999999L).setmealId(999999L)
                    .dishId(dishId).name("client forged name").price(BigDecimal.ZERO).copies(2).build()));

            dto.setName(prefix + "_insertFail");
            doThrow(new IllegalStateException("reference create rollback check"))
                    .when(relations).insertBatch(anyList());
            BaseContext.setCurrentId(employeeId);
            assertThrows(IllegalStateException.class, () -> service.saveWithDish(dto));
            reset(relations);
            assertEquals(0, jdbc.queryForObject("select count(*) from setmeal where name=?", Integer.class, dto.getName()));
            dto.setName(prefix + "_meal");
            System.out.println("PASS: failed relation insert rolls back new meal row");

            request(HttpMethod.POST, "/admin/setmeal", dto, 1);
            Long id = jdbc.queryForObject("select id from setmeal where name=?", Long.class, dto.getName());
            assertNotEquals(999999L, id);
            assertEquals(0, jdbc.queryForObject("select status from setmeal where id=?", Integer.class, id));
            assertEquals(employeeId, jdbc.queryForObject("select create_user from setmeal where id=?", Long.class, id));
            Map<String, Object> relation = jdbc.queryForMap("select * from setmeal_dish where setmeal_id=?", id);
            assertEquals(prefix + "_dish", relation.get("name"));
            assertEquals(new BigDecimal("6.50"), relation.get("price"));
            assertEquals(id, ((Number) relation.get("setmeal_id")).longValue());
            System.out.println("PASS: create defaults to closed, generated IDs, audit fields and server-derived dish fields");

            request(HttpMethod.POST, "/admin/setmeal", dto, 0);
            assertEquals(1, jdbc.queryForObject("select count(*) from setmeal where name=?", Integer.class, dto.getName()));
            Map<String, Object> page = (Map<String, Object>) request(HttpMethod.GET,
                    "/admin/setmeal/page?page=1&pageSize=10&name=" + prefix + "&categoryId=" + category + "&status=0", null, 1).get("data");
            assertEquals(1, ((Number) page.get("total")).intValue());
            assertNotNull(((List<Map<String, Object>>) page.get("records")).get(0).get("categoryName"));
            Map<String, Object> detail = (Map<String, Object>) request(HttpMethod.GET, "/admin/setmeal/" + id, null, 1).get("data");
            assertEquals(1, ((List<?>) detail.get("setmealDishes")).size());
            System.out.println("PASS: duplicate rejection, filtered paging, category name and detail relations");

            dto.setId(id);
            dto.setName(prefix + "_updated");
            dto.setStatus(null);
            dto.getSetmealDishes().get(0).setCopies(3);
            request(HttpMethod.PUT, "/admin/setmeal", dto, 1);
            request(HttpMethod.PUT, "/admin/setmeal", dto, 1);
            assertEquals(1, jdbc.queryForObject("select count(*) from setmeal_dish where setmeal_id=?", Integer.class, id));
            assertEquals(3, jdbc.queryForObject("select copies from setmeal_dish where setmeal_id=?", Integer.class, id));
            assertEquals(0, jdbc.queryForObject("select status from setmeal where id=?", Integer.class, id));
            System.out.println("PASS: repeated edits replace relations without duplicates and preserve omitted status");

            Map<String, Object> oldMeal = jdbc.queryForMap("select * from setmeal where id=?", id);
            Map<String, Object> oldRelation = jdbc.queryForMap("select * from setmeal_dish where setmeal_id=?", id);
            doThrow(new IllegalStateException("reference rollback check")).when(relations).insertBatch(anyList());
            dto.setDescription("must roll back");
            BaseContext.setCurrentId(employeeId);
            assertThrows(IllegalStateException.class, () -> service.update(dto));
            reset(relations);
            assertEquals(oldMeal, jdbc.queryForMap("select * from setmeal where id=?", id));
            assertEquals(oldRelation, jdbc.queryForMap("select * from setmeal_dish where setmeal_id=?", id));
            System.out.println("PASS: interrupted relation insert rolls back real main-row update and old-relation delete");

            dto.setDescription(null);
            dto.setSetmealDishes(List.of());
            request(HttpMethod.PUT, "/admin/setmeal", dto, 0);
            dto.setSetmealDishes(List.of(SetmealDish.builder().dishId(dishId).copies(0).build()));
            request(HttpMethod.PUT, "/admin/setmeal", dto, 0);
            dto.setSetmealDishes(List.of(SetmealDish.builder().dishId(stoppedId).copies(1).build()));
            request(HttpMethod.PUT, "/admin/setmeal", dto, 1);
            request(HttpMethod.POST, "/admin/setmeal/status/1?id=" + id, null, 0);
            assertEquals(0, jdbc.queryForObject("select status from setmeal where id=?", Integer.class, id));
            dto.setSetmealDishes(List.of(SetmealDish.builder().dishId(dishId).copies(1).build()));
            request(HttpMethod.PUT, "/admin/setmeal", dto, 1);
            request(HttpMethod.POST, "/admin/setmeal/status/2?id=" + id, null, 0);
            request(HttpMethod.POST, "/admin/setmeal/status/1?id=" + id, null, 1);
            assertEquals(1, jdbc.queryForObject("select status from setmeal where id=?", Integer.class, id));
            System.out.println("PASS: invalid dishes and copies rejected; real dish state controls enabling");

            dto.setName(prefix + "_guard");
            request(HttpMethod.POST, "/admin/setmeal", dto, 1);
            Long guard = jdbc.queryForObject("select id from setmeal where name=?", Long.class, dto.getName());
            request(HttpMethod.DELETE, "/admin/setmeal?ids=" + id + "," + guard, null, 0);
            assertEquals(2, jdbc.queryForObject("select count(*) from setmeal where id in (?,?)", Integer.class, id, guard));
            request(HttpMethod.POST, "/admin/setmeal/status/0?id=" + id, null, 1);

            List<Map<String, Object>> oldMeals = jdbc.queryForList("select * from setmeal where id in (?,?) order by id", id, guard);
            List<Map<String, Object>> oldRelations = jdbc.queryForList("select * from setmeal_dish where setmeal_id in (?,?) order by id", id, guard);
            doThrow(new IllegalStateException("reference delete rollback check"))
                    .when(meals).deleteByIds(anyList());
            assertThrows(IllegalStateException.class, () -> service.deleteBatch(List.of(id, guard)));
            reset(meals);
            assertEquals(oldMeals, jdbc.queryForList("select * from setmeal where id in (?,?) order by id", id, guard));
            assertEquals(oldRelations, jdbc.queryForList("select * from setmeal_dish where setmeal_id in (?,?) order by id", id, guard));
            System.out.println("PASS: failed main-row deletion rolls back deleted relation rows");

            request(HttpMethod.DELETE, "/admin/setmeal?ids=" + id + "," + guard, null, 1);
            assertEquals(0, jdbc.queryForObject("select count(*) from setmeal_dish where setmeal_id in (?,?)", Integer.class, id, guard));
            assertEquals(0, jdbc.queryForObject("select count(*) from setmeal where id in (?,?)", Integer.class, id, guard));
            assertEquals(2, jdbc.queryForObject("select count(*) from dish where name like ?", Integer.class, prefix + "%"));
            System.out.println("PASS: mixed-status batch rejected atomically; closed batch deletes only meals and relations");
        } finally {
            reset(relations);
            reset(meals);
            BaseContext.removeCurrentId();
            jdbc.update("delete from setmeal_dish where setmeal_id in (select id from setmeal where name like ?)", prefix + "%");
            jdbc.update("delete from setmeal where name like ?", prefix + "%");
            jdbc.update("delete from dish where name like ?", prefix + "%");
            assertEquals(0, jdbc.queryForObject("select count(*) from setmeal where name like ?", Integer.class, prefix + "%"));
            assertEquals(0, jdbc.queryForObject("select count(*) from dish where name like ?", Integer.class, prefix + "%"));
            System.out.println("PASS: this run's temporary rows cleaned");
        }
    }
}
