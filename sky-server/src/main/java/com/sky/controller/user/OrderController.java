package com.sky.controller.user;

import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.result.Result;
import com.sky.result.PageResult;
import com.sky.vo.OrderVO;
import com.sky.service.OrderService;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController("userOrderController")
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

    @GetMapping("/historyOrders")
    @ApiOperation("历史订单")
    public Result<PageResult> historyOrders(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Integer status) {
        return Result.success(orderService.pageQuery4User(page, pageSize, status));
    }

    @GetMapping("/orderDetail/{id}")
    @ApiOperation ("用户订单详情")
    public Result<OrderVO> details(@PathVariable Long id) {
        return Result.success(orderService.userDetails(id));
    }

    @PutMapping ("/cancel/{id}")
    @ApiOperation("用户取消订单")
    public Result<Void> cancel(@PathVariable Long id) {
        orderService.userCancelById(id);
        return Result.success();
    }

    @PostMapping ("/repetition/{id}")
    @ApiOperation ("再来一单")
    public Result<Void> repetition(@PathVariable Long id) {
        orderService.repetition(id);
        return Result.success();
    }

    @GetMapping("/reminder/{id}")
    @ApiOperation("用户催单")
    public Result<Void> reminder(@PathVariable Long id) {
        orderService.reminder(id);
        return Result.success();
    }
}
