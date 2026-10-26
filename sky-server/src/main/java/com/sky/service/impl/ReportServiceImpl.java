package com.sky.service.impl;

import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.Orders;
import com.sky.exception.BaseException;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.vo.OrderReportVO;
import com.sky.vo.SalesTop10ReportVO;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service 
public class ReportServiceImpl implements ReportService {
    @Autowired 
    private OrderMapper orderMapper;

    @Autowired 
    private UserMapper userMapper;

    private void checkRange(LocalDate begin, LocalDate end) {
        if (begin == null || end == null || begin.isAfter(end)) {
            throw new BaseException("开始日期和结束日期不能为空，且开始日期不能晚于结束日期");
        }

        if (ChronoUnit.DAYS.between(begin, end) >= 366 || end.equals(LocalDate.MAX)) {
            throw new BaseException("统计区间最多 366 天, 结束日期必须能够计算次日");
        }
    }

    private List<LocalDate> dates(LocalDate begin, LocalDate end) {
        checkRange(begin, end);
        List<LocalDate> result = new ArrayList<>();
        for (LocalDate date = begin; !date.isAfter(end); date = date.plusDays(1)) {
            result.add(date);
        }
        return result;
    }

    @Override 
    public TurnoverReportVO getTurnover(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = dates(begin, end);
        List<BigDecimal> turnoverList = new ArrayList<>();
        for (LocalDate date : dateList) {
            BigDecimal amount = orderMapper.sumByMap(orderConditions(date, Orders.COMPLETED));
            turnoverList.add(amount == null ? BigDecimal.ZERO :amount);
        }
        return TurnoverReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .turnoverList(StringUtils.join(turnoverList,","))
                .build();
    }
    
    @Override
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = dates(begin, end);
        List<Integer> newUserList = new ArrayList<>();
        List<Integer> totalUserList = new ArrayList<>();
        for (LocalDate date : dateList) {
            LocalDateTime nextDay = date.plusDays(1).atStartOfDay();
            newUserList.add(getUserCount(date.atStartOfDay(), nextDay));
            // 累计用户不设置起点，必须包含本次查询区间之前注册的用户。
            totalUserList.add(getUserCount(null, nextDay));
        }
        return UserReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .newUserList(StringUtils.join(newUserList, ","))
                .totalUserList(StringUtils.join(totalUserList, ","))
                .build();
    }

    private Integer getUserCount(LocalDateTime begin, LocalDateTime end) {
        Map<String, Object> params = new HashMap<>();
        params.put("begin", begin);
        params.put("end", end);
        return userMapper.countByMap(params);
    }

    @Override
    public OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> dateList = dates(begin, end);
        List<Integer> orderCountList = new ArrayList<>();
        List<Integer> validOrderCountList = new ArrayList<>();
        int total = 0;
        int valid = 0;
        for (LocalDate date : dateList) {
            int count = orderMapper.countByMap(orderConditions(date, null));
            int completed = orderMapper.countByMap(orderConditions(date, Orders.COMPLETED));
            orderCountList.add(count);
            validOrderCountList.add(completed);
            total += count;
            valid += completed;
        }
        return OrderReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .orderCountList(StringUtils.join(orderCountList, ","))
                .validOrderCountList(StringUtils.join(validOrderCountList, ","))
                .totalOrderCount(total)
                .validOrderCount(valid)
                .orderCompletionRate(total == 0 ? 0.0 : (double) valid / total)
                .build();
    }

    private Map<String, Object> orderConditions(LocalDate date, Integer status) {
        Map<String, Object> params = new HashMap<>();
        params.put("begin", date.atStartOfDay());
        params.put("end", date.plusDays(1).atStartOfDay());
        params.put("status", status);
        return params;
    }

    @Override
    public SalesTop10ReportVO getSalesTop10(LocalDate begin, LocalDate end) {
        checkRange(begin, end);
        List<GoodsSalesDTO> sales = orderMapper.getSalesTop10(
                begin.atStartOfDay(), end.plusDays(1).atStartOfDay());
        return SalesTop10ReportVO.builder()
                .nameList(sales.stream().map(GoodsSalesDTO::getName).collect(Collectors.joining(",")))
                .numberList(sales.stream().map(item -> item.getNumber().toString())
                        .collect(Collectors.joining(",")))
                .build();
    }
}
