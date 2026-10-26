package com.sky.service;

import com.alibaba.fastjson.JSONObject;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.dto.*;
import com.sky.result.PageResult;
import com.sky.vo.OrderVO;
import com.sky.vo.OrderStatisticsVO;

public interface OrderService {
    OrderSubmitVO submitOrder(OrdersSubmitDTO dto);

    OrderPaymentVO payment(OrdersPaymentDTO dto);

    // 仅由通过验签和解密的微信支付通知调用，不使用用户请求的 BaseContext。
    void paySuccess(JSONObject transaction);

    PageResult pageQuery4User(int page, int pageSize, Integer status);
    PageResult conditionSearch(OrdersPageQueryDTO query);
    OrderVO userDetails(Long id);
    OrderVO details(Long id);
    OrderStatisticsVO statistics();
    void userCancelById(Long id);
    void repetition(Long id);
    void confirm(OrdersConfirmDTO dto);
    void rejection(OrdersRejectionDTO dto);
    void cancel(OrdersCancelDTO dto);
    void delivery(Long id);
    void complete(Long id);

    void reminder(Long id);
}
