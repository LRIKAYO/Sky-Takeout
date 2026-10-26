package com.sky.task;

import com.sky.websocket.WebSocketServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 仅入门页面演示使用；业务联调关闭，避免管理端 JSON.parse 收到普通文本。 */
@Component
@ConditionalOnProperty(prefix = "sky.day10", name = "demo-enabled", havingValue = "true")
public class WebSocketTask {
    @Autowired
    private WebSocketServer webSocketServer;

    @Scheduled(cron = "0/5 * * * * ?", zone = "Asia/Shanghai")
    public void sendMessageToClient() {
        webSocketServer.sendToAllClient("这是服务端演示消息：" + LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
    }
}