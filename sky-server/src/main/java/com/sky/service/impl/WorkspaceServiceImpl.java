package com.sky.service.impl;

import com.sky.constant.StatusConstant;
import com.sky.entity.Orders;
import com.sky.exception.BaseException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.WorkspaceService;
import com.sky.vo.BusinessDataVO;
import com.sky.vo.DishOverViewVO;
import com.sky.vo.OrderOverViewVO;
import com.sky.vo.SetmealOverViewVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

@Service
public class WorkspaceServiceImpl implements WorkspaceService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;

    @Override
    public BusinessDataVO getBusinessData(LocalDateTime begin, LocalDateTime end) {
        if (begin == null || end == null || !begin.isBefore(end)) {
            throw new BaseException("运营统计的起止时间不能为空，且开始时间必须早于结束时间");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("begin", begin);
        params.put("end", end);
        params.put("status", null);
        int total = orderMapper.countByMap(params);

        params.put("status", Orders.COMPLETED);
        BigDecimal amount = orderMapper.sumByMap(params);
        BigDecimal turnover = amount == null ? BigDecimal.ZERO : amount;
        int valid = orderMapper.countByMap(params);
        double unitPrice = valid == 0 ? 0.0 : turnover
                .divide(BigDecimal.valueOf(valid), 2, RoundingMode.HALF_UP).doubleValue();

        params.remove("status");
        int newUsers = userMapper.countByMap(params);
        return BusinessDataVO.builder()
                // 现有 VO 约定为 Double；金额计算结束后再转换，用于展示和 Excel。
                .turnover(turnover.doubleValue())
                .validOrderCount(valid)
                .orderCompletionRate(total == 0 ? 0.0 : (double) valid / total)
                .unitPrice(unitPrice)
                .newUsers(newUsers)
                .build();
    }

    @Override
    public OrderOverViewVO getOrderOverView() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        Map<String, Object> params = new HashMap<>();
        params.put("begin", today.atStartOfDay());
        params.put("end", today.plusDays(1).atStartOfDay());
        params.put("status", Orders.TO_BE_CONFIRMED);
        int waiting = orderMapper.countByMap(params);
        params.put("status", Orders.CONFIRMED);
        int delivered = orderMapper.countByMap(params);
        params.put("status", Orders.COMPLETED);
        int completed = orderMapper.countByMap(params);
        params.put("status", Orders.CANCELLED);
        int cancelled = orderMapper.countByMap(params);
        params.put("status", null);
        int all = orderMapper.countByMap(params);
        return OrderOverViewVO.builder()
                .waitingOrders(waiting)
                .deliveredOrders(delivered)
                .completedOrders(completed)
                .cancelledOrders(cancelled)
                .allOrders(all)
                .build();
    }

    @Override
    public DishOverViewVO getDishOverView() {
        Map<String, Object> params = new HashMap<>();
        params.put("status", StatusConstant.ENABLE);
        int sold = dishMapper.countByMap(params);
        params.put("status", StatusConstant.DISABLE);
        int discontinued = dishMapper.countByMap(params);
        return DishOverViewVO.builder().sold(sold).discontinued(discontinued).build();
    }

    @Override
    public SetmealOverViewVO getSetmealOverView() {
        Map<String, Object> params = new HashMap<>();
        params.put("status", StatusConstant.ENABLE);
        int sold = setmealMapper.countByMap(params);
        params.put("status", StatusConstant.DISABLE);
        int discontinued = setmealMapper.countByMap(params);
        return SetmealOverViewVO.builder().sold(sold).discontinued(discontinued).build();
    }
}
