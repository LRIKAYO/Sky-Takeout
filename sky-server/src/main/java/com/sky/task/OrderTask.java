package com.sky.task;

import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "sky.day10", name = "order-task-enabled", havingValue = "true", matchIfMissing = true)
public class OrderTask {
    @Autowired
    private OrderMapper orderMapper;

    @Scheduled(cron = "0 * * * * ?", zone = "Asia/Shanghai")
    public void processTimeoutOrder() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        int count = orderMapper.cancelTimeoutOrders(now.minusMinutes(15), now);
        log.info("支付超时订单检查完成，取消 {} 单", count);
    }

    @Scheduled(cron = "0 0 1 * * ?", zone = "Asia/Shanghai")
    public void processDeliveryOrder() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        int count = orderMapper.completeDeliveryOrders(now.minusHours(1), now);
        log.info("派送订单检查完成，自动完成 {} 单", count);
    }
}