package com.sky.service.impl;

import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.Orders;
import com.sky.exception.BaseException;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.service.WorkspaceService;
import com.sky.vo.BusinessDataVO;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.time.ZoneId;
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

    @Autowired
    private WorkspaceService workspaceService;

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

    @Override
    public void exportBusinessData(HttpServletResponse response) throws IOException {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        LocalDate begin = today.minusDays(30);
        LocalDate end = today.minusDays(1);
        InputStream input = getClass().getResourceAsStream("/template/运营数据报表模板.xlsx");
        if (input == null) {
            throw new BaseException("找不到运营数据报表模板，请检查 resources/template 目录和文件后缀");
        }
        try (InputStream template = input;
             XSSFWorkbook excel = new XSSFWorkbook(template);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            XSSFSheet sheet = excel.getSheet("Sheet1");
            if (sheet == null) throw new BaseException("报表模板缺少 Sheet1");

            BusinessDataVO summary = workspaceService.getBusinessData(
                    begin.atStartOfDay(), today.atStartOfDay());
            sheet.getRow(1).getCell(1).setCellValue(begin + "至" + end);
            sheet.getRow(3).getCell(2).setCellValue(summary.getTurnover());
            sheet.getRow(3).getCell(4).setCellValue(summary.getOrderCompletionRate());
            sheet.getRow(3).getCell(6).setCellValue(summary.getNewUsers());
            sheet.getRow(4).getCell(2).setCellValue(summary.getValidOrderCount());
            sheet.getRow(4).getCell(4).setCellValue(summary.getUnitPrice());

            for (int i = 0; i < 30; i++) {
                LocalDate date = begin.plusDays(i);
                BusinessDataVO daily = workspaceService.getBusinessData(
                        date.atStartOfDay(), date.plusDays(1).atStartOfDay());
                XSSFRow row = sheet.getRow(7 + i);
                row.getCell(1).setCellValue(date.toString());
                row.getCell(2).setCellValue(daily.getTurnover());
                row.getCell(3).setCellValue(daily.getValidOrderCount());
                row.getCell(4).setCellValue(daily.getOrderCompletionRate());
                row.getCell(5).setCellValue(daily.getUnitPrice());
                row.getCell(6).setCellValue(daily.getNewUsers());
            }
            // 先完成小型报表，再设置下载响应，模板或查询失败时不发送半个 Excel。
            // ponytail: 固定30行使用内存缓冲；大批量导出时再改为流式方案。
            excel.write(buffer);
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=\"business-data-"
                    + begin + "_" + end + ".xlsx\"");
            response.setContentLength(buffer.size());
            buffer.writeTo(response.getOutputStream());
            response.flushBuffer();
        }
    }
}
