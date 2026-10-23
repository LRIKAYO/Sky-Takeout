package com.sky.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.*;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.*;
import com.sky.properties.WeChatProperties;
import com.sky.service.AddressBookService;
import com.sky.service.OrderService;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service 
public class OrderServiceImpl implements OrderService {
    // computOrderInfo计费： 商品合计 + 6元配送费 + 每件1元打包费
    private static final BigDecimal DELIVERY_FEE = new BigDecimal("6.00");
    @Autowired 
    private OrderMapper orderMapper;

    @Autowired 
    private OrderDetailMapper orderDetailMapper;

    @Autowired 
    private ShoppingCartMapper shoppingCartMapper;

    @Autowired 
    private AddressBookService addressBookService;

    @Autowired 
    private AddressBookMapper addressBookMapper;

    @Autowired 
    private WeChatPayUtil weChatPayUtil;

    @Autowired
    private UserMapper userMapper;

    @Autowired 
    private WeChatProperties weChatProperties;

    @Override 
    @Transactional 
    public OrderSubmitVO submitOrder(OrdersSubmitDTO dto) {
        Long userId = currentUser();
        validateSubmit(dto);
        // 复用地址簿已有的用户行锁，使同一用户的两次提交顺序读取和清空购物车。
        if (addressBookMapper.lockUser(userId) == null) fail("用户不存在");
        AddressBook address = addressBookService.getById(dto.getAddressBookId());
        List<ShoppingCart> carts = shoppingCartMapper.list(ShoppingCart.builder().userId(userId).build());
        if (carts == null || carts.isEmpty()) fail(MessageConstant.SHOPPING_CART_IS_NULL);

        BigDecimal goods = BigDecimal.ZERO;
        int quantity = 0;
        for (ShoppingCart cart : carts) {
            if (cart.getNumber() == null || cart.getNumber() <= 0 || cart.getNumber() > 1000 ||
                    cart.getAmount() == null || cart.getAmount().signum() <= 0) {
                fail("购物车数量或单价异常，请重新添加商品");
            }
            quantity = Math.addExact(quantity, cart.getNumber());
            goods = goods.add(cart.getAmount().multiply(BigDecimal.valueOf(cart.getNumber())));
        }
        BigDecimal total = goods.add(BigDecimal.valueOf(quantity)).add(DELIVERY_FEE);
        if (total.compareTo(new BigDecimal("99999999.99")) > 0 ||
                dto.getAmount().compareTo(total) != 0 || !Objects.equals(dto.getPackAmount(), quantity)) {
            fail("金额或打包费与购物车不一致，请刷新后重试");
        }

        LocalDateTime now = LocalDateTime.now();
        Orders order = new Orders();
        // DTO 餐具数量可为 null，实体是 int，须单独处理以避免属性拷贝失败。
        BeanUtils.copyProperties(dto, order, "tablewareNumber");
        order.setNumber(UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(userId);
        order.setAmount(total);
        order.setPackAmount(quantity);
        order.setStatus(Orders.PENDING_PAYMENT);
        order.setPayStatus(Orders.UN_PAID);
        order.setOrderTime(now);
        order.setPhone(address.getPhone());
        order.setConsignee(address.getConsignee());
        order.setAddress(text(address.getProvinceName()) + text(address.getCityName()) +
                text(address.getDistrictName()) + text(address.getDetail()));
        if (order.getConsignee() == null || order.getConsignee().length() > 32 || order.getAddress().length() > 255) {
            fail("订单收货人不能超过32字，完整收货地址不能超过255字，请修改地址后重试");
        }
        if (order.getEstimatedDeliveryTime() == null) order.setEstimatedDeliveryTime(now.plusHours(1));
        order.setTablewareNumber(dto.getTablewareNumber() == null ? 0 : dto.getTablewareNumber());
        orderMapper.insert(order);
        if (order.getId() == null) fail("订单主键未回填");

        List<OrderDetail> details = new ArrayList<>();
        for (ShoppingCart cart : carts) {
            OrderDetail detail = new OrderDetail();
            BeanUtils.copyProperties(cart, detail, "id");
            detail.setOrderId(order.getId());
            details.add(detail);
        }
        orderDetailMapper.insertBatch(details);
        shoppingCartMapper.deleteByUserId(userId);
        return OrderSubmitVO.builder().id(order.getId()).orderNumber(order.getNumber())
                .orderAmount(order.getAmount()).orderTime(order.getOrderTime()).build();
    }

    @Override
    public OrderPaymentVO payment(OrdersPaymentDTO dto) {
        Long userId = currentUser();
        if (dto == null || blank(dto.getOrderNumber()) || !Integer.valueOf(1).equals(dto.getPayMethod())) {
            fail("订单号不能为空且仅支持微信支付");
        }
        Orders order = orderMapper.getByNumberAndUserId(dto.getOrderNumber(), userId);
        if (order == null) fail(MessageConstant.ORDER_NOT_FOUND);
        if (!Orders.PENDING_PAYMENT.equals(order.getStatus()) || !Orders.UN_PAID.equals(order.getPayStatus())) {
            fail(MessageConstant.ORDER_STATUS_ERROR);
        }
        User user = userMapper.getById(userId);
        if (user == null || blank(user.getOpenid())) fail("微信用户不存在");
        requirePaymentConfig();
        try {
            JSONObject result = weChatPayUtil.pay(order.getNumber(), order.getAmount(), "苍穹外卖订单", user.getOpenid());
            // 这里只生成小程序调起支付的参数，不能将订单提前改成已支付。
            if (result == null || blank(result.getString("package")) || !result.getString("package").startsWith("prepay_id=") ||
                    blank(result.getString("paySign")) || blank(result.getString("timeStamp")) ||
                    blank(result.getString("nonceStr")) || !"RSA".equals(result.getString("signType"))) {
                fail("微信预支付失败，请检查支付配置或查单后重试");
            }
            OrderPaymentVO vo = result.toJavaObject(OrderPaymentVO.class);
            vo.setPackageStr(result.getString("package"));
            return vo;
        } catch (OrderBusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new OrderBusinessException("请求微信支付失败，请稍后重试");
        }
    }

    @Override
    @Transactional
    public void paySuccess(JSONObject transaction) {
        if (blank(weChatProperties.getAppid()) || blank(weChatProperties.getMchid()) ||
                transaction == null || !"SUCCESS".equals(transaction.getString("trade_state")) ||
                !Objects.equals(weChatProperties.getAppid(), transaction.getString("appid")) ||
                !Objects.equals(weChatProperties.getMchid(), transaction.getString("mchid")) ||
                blank(transaction.getString("transaction_id")) || blank(transaction.getString("out_trade_no"))) {
            fail("支付通知内容不匹配");
        }
        Orders order = orderMapper.getByNumberForUpdate(transaction.getString("out_trade_no"));
        if (order == null) fail(MessageConstant.ORDER_NOT_FOUND);
        JSONObject amount = transaction.getJSONObject("amount");
        if (amount == null || !"CNY".equals(amount.getString("currency")) ||
                amount.getBigDecimal("total") == null || order.getAmount() == null ||
                amount.getBigDecimal("total").compareTo(order.getAmount().movePointRight(2)) != 0) {
            fail("支付通知金额不匹配");
        }
        User user = userMapper.getById(order.getUserId());
        JSONObject payer = transaction.getJSONObject("payer");
        if (user == null || payer == null || !Objects.equals(user.getOpenid(), payer.getString("openid"))) {
            fail("支付用户不匹配");
        }
        // 重复通知不能覆盖已接单、已完成或已退款的后续状态。
        if (Orders.PAID.equals(order.getPayStatus()) || Orders.REFUND.equals(order.getPayStatus())) return;
        if (!Orders.PENDING_PAYMENT.equals(order.getStatus()) || !Orders.UN_PAID.equals(order.getPayStatus())) {
            fail(MessageConstant.ORDER_STATUS_ERROR);
        }
        try {
            order.setCheckoutTime(OffsetDateTime.parse(transaction.getString("success_time"))
                    .atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
        } catch (RuntimeException ex) {
            throw new OrderBusinessException("支付成功时间不正确");
        }
        if (orderMapper.markPaid(order) != 1) fail("订单状态已变化，请核对支付结果");
    }

    private void validateSubmit(OrdersSubmitDTO dto) {
        if (dto == null || dto.getAddressBookId() == null || dto.getAddressBookId() <= 0 ||
                dto.getPayMethod() != 1 || dto.getAmount() == null || dto.getAmount().signum() <= 0 ||
                dto.getPackAmount() == null || dto.getPackAmount() < 0 ||
                (!Integer.valueOf(0).equals(dto.getDeliveryStatus()) && !Integer.valueOf(1).equals(dto.getDeliveryStatus())) ||
                (!Integer.valueOf(0).equals(dto.getTablewareStatus()) && !Integer.valueOf(1).equals(dto.getTablewareStatus())) ||
                (dto.getTablewareNumber() != null && (dto.getTablewareNumber() < 0 || dto.getTablewareNumber() > 1000)) ||
                (dto.getRemark() != null && dto.getRemark().length() > 100)) {
            fail("下单参数不正确");
        }
        if (Integer.valueOf(0).equals(dto.getDeliveryStatus()) && (dto.getEstimatedDeliveryTime() == null ||
                !dto.getEstimatedDeliveryTime().isAfter(LocalDateTime.now()))) {
            fail("预约配送时间必须晚于当前时间");
        }
    }

    private void requirePaymentConfig() {
        for (String value : new String[]{weChatProperties.getAppid(), weChatProperties.getMchid(),
                weChatProperties.getMchSerialNo(), weChatProperties.getPrivateKeyFilePath(),
                weChatProperties.getWeChatPayCertFilePath(), weChatProperties.getApiV3Key(), weChatProperties.getNotifyUrl()}) {
            if (blank(value)) fail("尚未配置真实微信支付参数");
        }
        if (weChatProperties.getApiV3Key().getBytes(java.nio.charset.StandardCharsets.UTF_8).length != 32) {
            fail("微信支付 API v3 密钥必须是32字节");
        }
    }

    private Long currentUser() {
        Long id = BaseContext.getCurrentId();
        if (id == null || id <= 0) fail(MessageConstant.USER_NOT_LOGIN);
        return id;
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String text(String value) { return value == null ? "" : value; }
    private void fail(String message) { throw new OrderBusinessException(message); 

    }
}
