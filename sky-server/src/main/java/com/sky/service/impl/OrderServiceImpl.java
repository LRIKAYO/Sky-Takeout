package com.sky.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.dto.*;
import com.sky.result.PageResult;
import com.sky.vo.OrderVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.service.ShoppingCartService;
import com.sky.service.DeliveryRangeChecker;
import com.sky.entity.*;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.*;
import com.sky.properties.WeChatProperties;
import com.sky.service.AddressBookService;
import com.sky.service.OrderService;
import com.sky.websocket.WebSocketServer;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderSubmitVO;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.http.WebSocket;
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

    @Autowired
    private ShoppingCartService shoppingCartService;

    @Autowired
    private DeliveryRangeChecker deliveryRangeChecker;

    @Autowired
    private WebSocketServer webSocketServer;

    @Override 
    @Transactional 
    public OrderSubmitVO submitOrder(OrdersSubmitDTO dto) {
        Long userId = currentUser();
        validateSubmit(dto);
        // 复用地址簿已有的用户行锁，使同一用户的两次提交顺序读取和清空购物车。
        if (addressBookMapper.lockUser(userId) == null) fail("用户不存在");
        AddressBook address = addressBookService.getById(dto.getAddressBookId());
        deliveryRangeChecker.check(address);
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
        notifyAfterCommit(1,order);
    }


    @Override
    public PageResult pageQuery4User(int page, int pageSize, Integer status) {
        OrdersPageQueryDTO query = new OrdersPageQueryDTO();
        query.setPage(page);
        query.setPageSize(pageSize);
        query.setStatus(status);
        query.setUserId(currentUser());
        return queryOrders(query);
    }

    @Override
    public PageResult conditionSearch(OrdersPageQueryDTO query) {
        if (query == null) fail("查询参数不能为空");
        // 管理端搜索契约没有 userId；不接受额外参数改变查询范围。
        query.setUserId(null);
        return queryOrders(query);
    }

    private PageResult queryOrders(OrdersPageQueryDTO query) {
        if (query.getPage() < 1 || query.getPageSize() < 1 || query.getPageSize() > 100 ||
                (query.getStatus() != null && (query.getStatus() < 1 || query.getStatus() > 6)) ||
                (query.getBeginTime() != null && query.getEndTime() != null &&
                        query.getBeginTime().isAfter(query.getEndTime()))) {
            fail("分页、状态或时间范围不正确");
        }
        Page<Orders> page;
        try {
            PageHelper.startPage(query.getPage(), query.getPageSize());
            page = orderMapper.pageQuery(query);
        } finally {
            PageHelper.clearPage();
        }
        List<OrderVO> records = new ArrayList<>();
        for (Orders order : page) records.add(toVO(order));
        return new PageResult(page.getTotal(), records);
    }

    @Override
    public OrderVO userDetails(Long id) {
        return toVO(ownedOrder(id, false));
    }

    @Override
    public OrderVO details(Long id) {
        return toVO(requireOrder(id, false));
    }

    private OrderVO toVO(Orders order) {
        List<OrderDetail> details = orderDetailMapper.getByOrderId(order.getId());
        OrderVO vo = new OrderVO();
        BeanUtils.copyProperties(order, vo);
        vo.setOrderDetailList(details);
        StringBuilder names = new StringBuilder();
        for (OrderDetail detail : details) {
            names.append(detail.getName()).append("*").append(detail.getNumber()).append(";");
        }
        vo.setOrderDishes(names.toString());
        return vo;
    }

    @Override
    public OrderStatisticsVO statistics() {
        OrderStatisticsVO vo = new OrderStatisticsVO();
        vo.setToBeConfirmed(orderMapper.countStatus(Orders.TO_BE_CONFIRMED));
        vo.setConfirmed(orderMapper.countStatus(Orders.CONFIRMED));
        vo.setDeliveryInProgress(orderMapper.countStatus(Orders.DELIVERY_IN_PROGRESS));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void userCancelById(Long id) {
        Orders order = ownedOrder(id, true);
        if (Orders.CANCELLED.equals(order.getStatus())) return;
        if (!Orders.PENDING_PAYMENT.equals(order.getStatus()) &&
                !Orders.TO_BE_CONFIRMED.equals(order.getStatus())) fail(MessageConstant.ORDER_STATUS_ERROR);
        cancelOrder(order, "用户取消", null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(OrdersConfirmDTO dto) {
        if (dto == null) fail("接单参数不能为空");
        Orders order = requireOrder(dto.getId(), true);
        requirePaidStatus(order, Orders.TO_BE_CONFIRMED);
        changeStatus(order, Orders.CONFIRMED, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejection(OrdersRejectionDTO dto) {
        if (dto == null) fail("拒单参数不能为空");
        reason(dto.getRejectionReason());
        Orders order = requireOrder(dto.getId(), true);
        requirePaidStatus(order, Orders.TO_BE_CONFIRMED);
        cancelOrder(order, null, dto.getRejectionReason().trim());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(OrdersCancelDTO dto) {
        if (dto == null) fail("取消参数不能为空");
        reason(dto.getCancelReason());
        Orders order = requireOrder(dto.getId(), true);
        if (Orders.CANCELLED.equals(order.getStatus())) return;
        // 课程未明确全部商家取消边界；本参考允许 1~4，已完成和已取消不可再推进。
        if (order.getStatus() == null || order.getStatus() < 1 || order.getStatus() > 4) {
            fail(MessageConstant.ORDER_STATUS_ERROR);
        }
        cancelOrder(order, dto.getCancelReason().trim(), null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delivery(Long id) {
        Orders order = requireOrder(id, true);
        requirePaidStatus(order, Orders.CONFIRMED);
        changeStatus(order, Orders.DELIVERY_IN_PROGRESS, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long id) {
        Orders order = requireOrder(id, true);
        requirePaidStatus(order, Orders.DELIVERY_IN_PROGRESS);
        changeStatus(order, Orders.COMPLETED, LocalDateTime.now());
    }

    private void requirePaidStatus(Orders order, Integer status) {
        if (!status.equals(order.getStatus()) || !Orders.PAID.equals(order.getPayStatus())) {
            fail(MessageConstant.ORDER_STATUS_ERROR);
        }
    }

    private void changeStatus(Orders order, Integer target, LocalDateTime deliveryTime) {
        if (orderMapper.changeStatus(order.getId(), order.getStatus(), target, deliveryTime) != 1) {
            fail("订单状态已变化，请刷新");
        }
    }

    private void cancelOrder(Orders order, String cancelReason, String rejectionReason) {
        Integer nextPayStatus = order.getPayStatus();
        if (Orders.PAID.equals(nextPayStatus)) {
            requirePaymentConfig();
            if (blank(weChatProperties.getRefundNotifyUrl())) fail("尚未配置退款通知地址");
            try {
                // 退款单号固定为原订单号：全额退款重试沿用同一号码，不重复生成。
                JSONObject response = JSONObject.parseObject(weChatPayUtil.refund(order.getNumber(),
                        order.getNumber(), order.getAmount(), order.getAmount()));
                String status = response == null ? null : response.getString("status");
                JSONObject amount = response == null ? null : response.getJSONObject("amount");
                if ((!"SUCCESS".equals(status) && !"PROCESSING".equals(status)) ||
                        !order.getNumber().equals(response.getString("out_trade_no")) ||
                        !order.getNumber().equals(response.getString("out_refund_no")) ||
                        amount == null || amount.getBigDecimal("refund") == null ||
                        amount.getBigDecimal("refund").compareTo(order.getAmount().movePointRight(2)) != 0) {
                    fail("退款结果异常，请按原退款单号查单核对");
                }
                // 沿用课程 pay_status=2 的“退款”语义，表示进入退款流程，不承诺到账。
                // PROCESSING 的最终结果还需退款通知/查单闭环；本参考未实现该后续模块。
                nextPayStatus = Orders.REFUND;
            } catch (OrderBusinessException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new OrderBusinessException("退款请求失败或结果未知，请按原退款单号查单核对");
            }
        } else if (!Orders.UN_PAID.equals(nextPayStatus) ||
                !Orders.PENDING_PAYMENT.equals(order.getStatus())) {
            fail("订单与支付状态不一致，请先核对");
        }
        if (orderMapper.cancelOrder(order.getId(), order.getStatus(), order.getPayStatus(),
                nextPayStatus, cancelReason, rejectionReason, LocalDateTime.now()) != 1) {
            fail("订单状态已变化，请刷新");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void repetition(Long id) {
        Long userId = currentUser();
        // 与现有下单、地址簿采用相同的用户行锁顺序。
        if (addressBookMapper.lockUser(userId) == null) fail("用户不存在");
        Orders order = ownedOrder(id, false);
        List<OrderDetail> details = orderDetailMapper.getByOrderId(order.getId());
        if (details.isEmpty()) fail("订单没有商品明细");
        for (OrderDetail detail : details) {
            if (detail.getNumber() == null || detail.getNumber() < 1 || detail.getNumber() > 1000) {
                fail("原订单商品数量异常");
            }
            ShoppingCartDTO dto = new ShoppingCartDTO();
            dto.setDishId(detail.getDishId());
            dto.setSetmealId(detail.getSetmealId());
            dto.setDishFlavor(detail.getDishFlavor());
            // ponytail: 课程小购物车复用现有加购校验；批量大订单再改为按数量更新。
            for (int i = 0; i < detail.getNumber(); i++) shoppingCartService.addShoppingCart(dto);
        }
    }

    private Orders ownedOrder(Long id, boolean lock) {
        Long userId = currentUser();
        Orders order = requireOrder(id, lock);
        if (!userId.equals(order.getUserId())) fail(MessageConstant.ORDER_NOT_FOUND);
        return order;
    }

    private Orders requireOrder(Long id, boolean lock) {
        if (id == null || id <= 0) fail("订单 ID 必须大于 0");
        Orders order = lock ? orderMapper.getByIdForUpdate(id) : orderMapper.getById(id);
        if (order == null) fail(MessageConstant.ORDER_NOT_FOUND);
        return order;
    }

    private void reason(String value) {
        if (blank(value) || value.trim().length() > 255) fail("原因不能为空且不能超过255字");
    }

    @Override
    public void reminder(Long id) {
        Orders order = ownedOrder(id, false);
        if (!Orders.PAID.equals(order.getPayStatus()) ||
                (!Orders.TO_BE_CONFIRMED.equals(order.getStatus()) &&
                 !Orders.CONFIRMED.equals(order.getStatus()) &&
                 !Orders.DELIVERY_IN_PROGRESS.equals(order.getStatus()))) {
            fail("当前订单状态不支持催单");
        }
        notifyAfterCommit(2, order);
    }

    private void notifyAfterCommit(int type, Orders order) {
        JSONObject message = new JSONObject();
        message.put("type", type);
        message.put("orderId", order.getId());
        message.put("content", "订单号：" + order.getNumber());
        String payload = message.toJSONString();
        Runnable send = () -> {
            try {
                webSocketServer.sendToAllClient(payload);
            } catch (RuntimeException ex) {
                // 提醒失败不能撤销已提交的付款，也不将消息内容和异常详情写进日志。
                org.slf4j.LoggerFactory.getLogger(OrderServiceImpl.class)
                        .warn("订单提醒发送失败，订单 ID={}", order.getId());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { send.run(); }
            });
        } else {
            send.run(); // 无写事务的催单请求可立即发送。
        }
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
