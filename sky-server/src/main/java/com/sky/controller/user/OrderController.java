package com.sky.controller.user;

import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.result.Result;
import com.sky.service.OrderService;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController 
@RequestMapping ("/user/order")
@Api(tags = "c端-订单接口")
public class OrderController {
    @Autowired 
    private OrderService orderService;

    @PostMapping ("submit")
    @ApiOperation ("用户下单")
    public Result<OrderSubmitVO> submit(@RequestBody OrdersSubmitDTO dto) {
        return Result.success(orderService.submitOrder(dto));
    }

    @PutMapping ("/payment")
    @ApiOperation ("订单支付")
    public Result<OrderPaymentVO> payment(@RequestBody OrdersPaymentDTO dto) {
        return Result.success(orderService.payment(dto));
    }
}