package com.sky.service.impl;

import com.sky.mapper.DishFlavorMapper;
import com.sky.service.DishService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;

/** Explicit integration check: run with -Dtest=DishDeletionTransactionIT. Uses only its own temporary rows. */
@SpringBootTest(properties = "sky.upload.mode=local")
class DishDeletionTransactionIT {

    @Autowired
    private DishService dishService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private DishFlavorMapper flavors;

    @Test
    void rollsBackBothTablesWhenFlavorDeletionFailsAfterWriting() {
        assertEquals("sky_take_out_course", jdbc.queryForObject("select database()", String.class));
        String jdbcUrl = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                connection -> connection.getMetaData().getURL());
        assertTrue(jdbcUrl.startsWith("jdbc:mysql://localhost:") || jdbcUrl.startsWith("jdbc:mysql://127.0.0.1:"));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(AopUtils.isAopProxy(dishService));
        Long categoryId = jdbc.queryForObject("select min(id) from category where type=1", Long.class);
        assertNotNull(categoryId);
        String prefix = "p44rb_" + UUID.randomUUID().toString().substring(0, 12);
        List<Long> ids = new ArrayList<>();
        try {
            for (String suffix : List.of("_a", "_b")) {
                KeyHolder key = new GeneratedKeyHolder();
                jdbc.update(connection -> {
                    PreparedStatement statement = connection.prepareStatement(
                            "insert into dish(name,category_id,price,image,description,status) values (?,?,1.00,'','',0)",
                            Statement.RETURN_GENERATED_KEYS);
                    statement.setString(1, prefix + suffix);
                    statement.setLong(2, categoryId);
                    return statement;
                }, key);
                Long id = key.getKey().longValue();
                ids.add(id);
                jdbc.update("insert into dish_flavor(dish_id,name,`value`) values (?,'rollback','[\"normal\"]')", id);
            }
            doAnswer(invocation -> {
                // The real DishMapper DELETE has already run inside the service transaction.
                assertEquals(0, count("dish", "id", ids));
                assertEquals(2, jdbc.update("delete from dish_flavor where dish_id in (?,?)", ids.get(0), ids.get(1)));
                throw new IllegalStateException("Expected failure after both table writes");
            }).when(flavors).deleteByDishIds(ids);

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> dishService.deleteBatch(ids));
            assertEquals("Expected failure after both table writes", failure.getMessage());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(2, count("dish", "id", ids));
            assertEquals(2, count("dish_flavor", "dish_id", ids));
        } finally {
            for (Long id : ids) {
                jdbc.update("delete from dish_flavor where dish_id=?", id);
                jdbc.update("delete from dish where id=? and name like ?", id, prefix + "%");
            }
        }
    }

    private int count(String table, String column, List<Long> ids) {
        // Table and column names are fixed by this test; IDs are JDBC parameters.
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " in (?,?)",
                Integer.class, ids.get(0), ids.get(1));
    }
}
