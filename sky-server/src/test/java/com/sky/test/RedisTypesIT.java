package com.sky.test;

import com.sky.config.RedisConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        classes = RedisConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ImportAutoConfiguration(RedisAutoConfiguration.class)
public class RedisTypesIT {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    public void testCommonTypes() {
        String prefix = "day05:java:" + UUID.randomUUID() + ":";
        String name = prefix + "name";
        String code = prefix + "code";
        String once = prefix + "once";
        String hash = prefix + "hash";
        String list = prefix + "list";
        String set1 = prefix + "set1";
        String set2 = prefix + "set2";
        String rank = prefix + "rank";
        List<String> ownKeys = Arrays.asList(name, code, once, hash, list, set1, set2, rank);

        try {
            // String：普通读写、有效期、仅不存在时设置。
            redisTemplate.opsForValue().set(name, "小明");
            assertEquals("小明", redisTemplate.opsForValue().get(name));
            redisTemplate.opsForValue().set(code, "1234", 3, TimeUnit.MINUTES);
            Long ttl = redisTemplate.getExpire(code, TimeUnit.SECONDS);
            assertNotNull(ttl);
            assertTrue(ttl > 0 && ttl <= 180);
            assertEquals(Boolean.TRUE, redisTemplate.opsForValue().setIfAbsent(once, 1));
            assertEquals(Boolean.FALSE, redisTemplate.opsForValue().setIfAbsent(once, 2));
            assertEquals(1, redisTemplate.opsForValue().get(once));

            // Hash：一个 key 下的 field/value。
            HashOperations<String, String, Object> hashes = redisTemplate.opsForHash();
            hashes.put(hash, "name", "tom");
            hashes.put(hash, "age", 20);
            assertEquals("tom", hashes.get(hash, "name"));
            assertEquals(new HashSet<>(Arrays.asList("name", "age")), hashes.keys(hash));
            assertEquals(2, hashes.values(hash).size());
            assertEquals(Long.valueOf(1), hashes.delete(hash, "age"));

            // List：从左依次插入 a、b、c，右侧弹出 a。
            redisTemplate.opsForList().leftPushAll(list, "a", "b", "c");
            assertEquals(Arrays.asList("c", "b", "a"),
                    redisTemplate.opsForList().range(list, 0, -1));
            assertEquals("a", redisTemplate.opsForList().rightPop(list));
            assertEquals(Long.valueOf(2), redisTemplate.opsForList().size(list));

            // Set：去重、交集、并集，不依赖遍历顺序。
            redisTemplate.opsForSet().add(set1, "a", "b", "c", "a");
            redisTemplate.opsForSet().add(set2, "b", "c", "d");
            assertEquals(Long.valueOf(3), redisTemplate.opsForSet().size(set1));
            assertEquals(new HashSet<>(Arrays.asList("a", "b", "c")),
                    redisTemplate.opsForSet().members(set1));
            assertEquals(new HashSet<>(Arrays.asList("b", "c")),
                    redisTemplate.opsForSet().intersect(set1, set2));
            assertEquals(new HashSet<>(Arrays.asList("a", "b", "c", "d")),
                    redisTemplate.opsForSet().union(set1, set2));
            assertEquals(Long.valueOf(1), redisTemplate.opsForSet().remove(set1, "a"));

            // ZSet：按 score 升序；断言迭代顺序。
            redisTemplate.opsForZSet().add(rank, "a", 10);
            redisTemplate.opsForZSet().add(rank, "b", 12);
            redisTemplate.opsForZSet().add(rank, "c", 9);
            assertArrayEquals(new Object[]{"c", "a", "b"},
                    redisTemplate.opsForZSet().range(rank, 0, -1).toArray());
            assertEquals(Double.valueOf(19), redisTemplate.opsForZSet().incrementScore(rank, "c", 10));
            assertArrayEquals(new Object[]{"a", "b", "c"},
                    redisTemplate.opsForZSet().range(rank, 0, -1).toArray());
            assertEquals(Long.valueOf(1), redisTemplate.opsForZSet().remove(rank, "b"));

            // 通用操作；仅查看本次练习前缀。
            assertEquals(Boolean.TRUE, redisTemplate.hasKey(name));
            assertEquals(DataType.STRING, redisTemplate.type(name));
            assertEquals(DataType.HASH, redisTemplate.type(hash));
            assertEquals(new HashSet<>(ownKeys), redisTemplate.keys(prefix + "*"));
            assertEquals(Boolean.TRUE, redisTemplate.delete(name));
            assertEquals(Boolean.FALSE, redisTemplate.hasKey(name));
        } finally {
            redisTemplate.delete(ownKeys);
        }
    }
}
