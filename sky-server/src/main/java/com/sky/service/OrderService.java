package com.sky.service;

import com.alibaba.fastjson.JSONObject;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;

public interface OrderService {
    OrderSubmitVO submitOrder(OrdersSubmitDTO dto);

    OrderPaymentVO payment(OrdersPaymentDTO dto);

    // 仅由通过验签和解密的微信支付通知调用，不使用用户请求的 BaseContext。
    void paySuccess(JSONObject transaction);
}