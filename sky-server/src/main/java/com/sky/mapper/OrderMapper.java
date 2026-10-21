package com.sky.mapper;

import com.sky.entity.Orders;
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
}