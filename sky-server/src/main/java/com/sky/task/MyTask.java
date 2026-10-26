package com.sky.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "sky.day10", name = "demo-enabled", havingValue = "true")
public class MyTask {
    @Scheduled(cron = "0/5 * * * * ?", zone = "Asia/Shanghai")
    public void executeTask() {
        log.info("五秒任务演示: {}", LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
    }
}
