package com.sky.test;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 本地 Redis 连接检查：使用真实应用配置，只读，不依赖 MySQL 或 Web 服务。
 * 显式运行 RedisConnectionIT，避免普通构建要求本机 Redis 在线。
 */
@SpringBootTest(classes = RedisAutoConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class RedisConnectionIT {

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Test
    public void shouldConnectWithConfiguredCredentials() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            assertEquals("PONG", connection.ping());
        }
    }
}
