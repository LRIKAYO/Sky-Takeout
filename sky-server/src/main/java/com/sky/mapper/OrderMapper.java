package com.sky.mapper;

import com.sky.entity.Orders;
import com.sky.dto.OrdersPageQueryDTO;
import com.github.pagehelper.Page;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.sky.dto.GoodsSalesDTO;
import org.apache.ibatis.annotations.*;
@Mapper
public interface OrderMapper {
    void insert(Orders order);

    @Select("select * from orders where number=#{number} and user_id=#{userId}")
    Orders getByNumberAndUserId(@Param("number") String number, @Param("userId") Long userId);

    @Select("select * from orders where number=#{number} for update")
    Orders getByNumberForUpdate(String number);

    @Update("update orders set status=2,pay_status=1,checkout_time=#{checkoutTime} " +
            "where id=#{id} and status=1 and pay_status=0")
    int markPaid(Orders order);

    Page<Orders> pageQuery(OrdersPageQueryDTO query);

    @Select("select * from orders where id=#{id}")
    Orders getById(Long id);

    @Select("select * from orders where id=#{id} for update")
    Orders getByIdForUpdate(Long id);

    @Select("select count(*) from orders where status=#{status}")
    Integer countStatus(Integer status);

    @Update("update orders set status=#{target}, " +
            "delivery_time=coalesce(#{deliveryTime,jdbcType=TIMESTAMP},delivery_time) " +
            "where id=#{id} and status=#{expected} and pay_status=1")
    int changeStatus(@Param("id") Long id, @Param("expected") Integer expected,
            @Param("target") Integer target, @Param("deliveryTime") LocalDateTime deliveryTime);

    @Update("update orders set status=6,pay_status=#{nextPay}," +
            "cancel_reason=#{cancelReason},rejection_reason=#{rejectionReason},cancel_time=#{cancelTime} " +
            "where id=#{id} and status=#{expected} and pay_status=#{expectedPay}")
    int cancelOrder(@Param("id") Long id, @Param("expected") Integer expected,
            @Param("expectedPay") Integer expectedPay, @Param("nextPay") Integer nextPay,
            @Param("cancelReason") String cancelReason, @Param("rejectionReason") String rejectionReason,
            @Param("cancelTime") LocalDateTime cancelTime);

    @Update("update orders set status=6,cancel_reason='支付超时，自动取消',cancel_time=#{now} " +
            "where status=1 and pay_status=0 and order_time < #{cutoff}")
    int cancelTimeoutOrders(@Param("cutoff") LocalDateTime cutoff,@Param("now") LocalDateTime now);

    @Update("update orders set status=5,delivery_time=coalesce(delivery_time,#{now}) " +
            "where status=4 and pay_status=1 and order_time < #{cutoff}")
    int completeDeliveryOrders(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);
    
    BigDecimal sumByMap(Map<String, Object> params);

    Integer countByMap(Map<String, Object> params);

    List<GoodsSalesDTO> getSalesTop10(@Param("begin") LocalDateTime begin,
                                      @Param ("end") LocalDateTime end);
}
