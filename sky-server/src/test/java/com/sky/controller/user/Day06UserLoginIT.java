package com.sky.controller.user;

import com.sky.context.BaseContext;
import com.sky.properties.JwtProperties;
import com.sky.result.Result;
import com.sky.utils.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 显式运行：真实 Spring MVC 与安全配置；不调用微信、不写数据库或 Redis。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sky.wechat.appid=", "sky.wechat.secret=",
                "spring.data.redis.repositories.enabled=false"})
@Import(Day06UserLoginIT.ProbeConfiguration.class)
class Day06UserLoginIT {
    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JwtProperties jwt;
    @MockBean(name = "redisTemplate")
    private RedisTemplate<String, Object> redis;

    @Test
    @SuppressWarnings("unchecked")
    void bootsWithUserSecurityAndPreservesPublicRoutes() {
        assertNotNull(jwt.getUserSecretKey());
        assertFalse(jwt.getUserSecretKey().isBlank());
        assertNotEquals(jwt.getAdminSecretKey(), jwt.getUserSecretKey());
        assertEquals(7200000, jwt.getUserTtl());
        assertEquals("authentication", jwt.getUserTokenName());

        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ResponseEntity<Map> shop = http.getForEntity("/user/shop/status", Map.class);
        assertEquals(HttpStatus.OK, shop.getStatusCode());
        assertEquals(0, ((Number) shop.getBody().get("data")).intValue());

        for (String code : new String[]{"", "fresh-code-without-config"}) {
            ResponseEntity<Map> login = http.postForEntity("/user/user/login", Map.of("code", code), Map.class);
            assertEquals(HttpStatus.OK, login.getStatusCode());
            assertEquals(0, ((Number) login.getBody().get("code")).intValue());
        }

        String userToken = JwtUtil.createJWT(jwt.getUserSecretKey(), jwt.getUserTtl(),
                new HashMap<>(Map.of("userId", 42L)));
        String adminToken = JwtUtil.createJWT(jwt.getAdminSecretKey(), jwt.getAdminTtl(),
                new HashMap<>(Map.of("empId", 1L)));
        assertEquals(HttpStatus.UNAUTHORIZED, request("/user/day06-check/current", null, null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, request("/user/day06-check/current", "authentication", "bad-token").getStatusCode());
        ResponseEntity<Map> user = request("/user/day06-check/current", "authentication", userToken);
        assertEquals(HttpStatus.OK, user.getStatusCode());
        assertEquals(42, ((Number) user.getBody().get("data")).intValue());
        ResponseEntity<Map> admin = request("/admin/day06-check/current", "token", adminToken);
        assertEquals(HttpStatus.OK, admin.getStatusCode());
        assertEquals(1, ((Number) admin.getBody().get("data")).intValue());
        assertEquals(HttpStatus.UNAUTHORIZED, request("/user/day06-check/current", "authentication", adminToken).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, request("/admin/day06-check/current", "token", userToken).getStatusCode());
        assertEquals(HttpStatus.OK, http.getForEntity("/doc.html", String.class).getStatusCode());
    }

    private ResponseEntity<Map> request(String path, String name, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (name != null) {
            headers.set(name, token);
        }
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean
        ProbeController day06ProbeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping({"/user/day06-check/current", "/admin/day06-check/current"})
        public Result<Long> current() {
            return Result.success(BaseContext.getCurrentId());
        }
    }
}
