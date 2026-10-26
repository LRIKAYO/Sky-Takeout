package com.sky.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 课程本机演示端点。sid 不是身份认证；接公网前须补管理身份校验和 WSS。 */
@Component
@ServerEndpoint("/ws/{sid}")
@Slf4j
public class WebSocketServer {
    // Endpoint 实例由 WebSocket 容器创建，业务注入的是 Spring 实例；共享会话表连接二者。
    // ponytail: 单 JVM 连接表；多实例部署时再引入跨实例消息分发。
    private static final ConcurrentMap<String, Session> SESSIONS = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session, @PathParam("sid") String sid) throws IOException {
        if (sid == null || sid.isBlank() || sid.length() > 64) {
            session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, "Invalid client id"));
            return;
        }
        // 按真实 session id 保存，两个标签页使用同一个 sid 时也不会互相覆盖。
        SESSIONS.put(session.getId(), session);
        log.info("WebSocket 连接建立，当前连接数={}", SESSIONS.size());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        // 仅观察客户端发来的消息，不将客户端原始内容广播到管理页面。
        log.info("收到 WebSocket 客户端消息，字符数={}", message == null ? 0 : message.length());
    }

    @OnClose
    public void onClose(Session session) {
        SESSIONS.remove(session.getId(), session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        if (session != null) {
            SESSIONS.remove(session.getId(), session);
            closeQuietly(session);
        }
    }

    public void sendToAllClient(String message) {
        for (Session session : SESSIONS.values()) {
            synchronized (session) {
                try {
                    if (!session.isOpen()) {
                        SESSIONS.remove(session.getId(), session);
                        continue;
                    }
                    session.getBasicRemote().sendText(message);
                } catch (Exception ex) {
                    SESSIONS.remove(session.getId(), session);
                    closeQuietly(session);
                    log.warn("已移除无法发送的 WebSocket 连接");
                }
            }
        }
    }

    private void closeQuietly(Session session) {
        try { session.close(); } catch (IOException ignored) { }
    }
}