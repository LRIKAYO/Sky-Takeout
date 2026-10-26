package com.sky.mapper;

import com.sky.entity.OrderDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderDetailMapper {
    void insertBatch(@Param("orderDetails") List<OrderDetail> orderDetails);

    @Select("select * from order_detail where order_id=#{orderId} order by id")
    List<OrderDetail> getByOrderId(Long orderId);
}