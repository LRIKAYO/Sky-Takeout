package com.sky.service.impl;

import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;
import com.sky.exception.LoginFailedException;
import com.sky.mapper.UserMapper;
import com.sky.properties.WeChatProperties;
import com.sky.utils.HttpClientUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Day06UserLoginTest {
    @Test
    void firstLoginCreatesUserAndSecondLoginReusesId() {
        UserMapper mapper = mock(UserMapper.class);
        UserServiceImpl service = loginService(mapper);
        doReturn("{\"openid\":\"test-openid\"}").when(service).requestWechat("fresh");
        doAnswer(inv -> { inv.<User>getArgument(0).setId(42L); return null; })
                .when(mapper).insert(any(User.class));
        UserLoginDTO dto = new UserLoginDTO();
        dto.setCode("fresh");
        User first = service.wxLogin(dto);
        assertEquals(42L, first.getId());
        assertNotNull(first.getCreateTime());
        when(mapper.getByOpenid("test-openid")).thenReturn(first);
        assertSame(first, service.wxLogin(dto));
        verify(mapper, times(1)).insert(any(User.class));
    }

    @Test
    void badCodeOrWechatResponseDoesNotWriteUser() {
        UserMapper mapper = mock(UserMapper.class);
        UserServiceImpl service = loginService(mapper);
        assertThrows(LoginFailedException.class, () -> service.wxLogin(new UserLoginDTO()));
        UserLoginDTO dto = new UserLoginDTO();
        dto.setCode("fresh");
        for (String response : List.of("", "not-json", "null", "{}",
                "{\"errcode\":40029}", "{\"openid\":\"  \"}")) {
            doReturn(response).when(service).requestWechat("fresh");
            assertThrows(LoginFailedException.class, () -> service.wxLogin(dto));
        }
        doThrow(new IllegalStateException("network failure")).when(service).requestWechat("fresh");
        assertThrows(LoginFailedException.class, () -> service.wxLogin(dto));
        verifyNoInteractions(mapper);
    }

    @Test
    void httpGetHandlesQueryAndHttpFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            byte[] body = exchange.getRequestURI().getRawQuery().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/failed", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            assertEquals("js_code=a%2Bb", HttpClientUtil.doGet(base + "/ok", Map.of("js_code", "a+b")));
            assertThrows(IllegalStateException.class, () -> HttpClientUtil.doGet(base + "/failed", null));
        } finally {
            server.stop(0);
        }
    }

    private UserServiceImpl loginService(UserMapper mapper) {
        UserServiceImpl service = spy(new UserServiceImpl());
        WeChatProperties props = new WeChatProperties();
        props.setAppid("unit-test-appid");
        props.setSecret("unit-test-secret");
        ReflectionTestUtils.setField(service, "userMapper", mapper);
        ReflectionTestUtils.setField(service, "weChatProperties", props);
        return service;
    }
}
