package com.sky.mapper;

import com.sky.entity.ShoppingCart;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface ShoppingCartMapper {
    List<ShoppingCart> list(ShoppingCart shoppingCart);

    @Update("update shopping_cart set number = number + 1 "
            + "where id = #{id} and user_id = #{userId}")
    void incrementNumber(@Param("id") Long id, @Param("userId") Long userId);

    @Update("update shopping_cart set number = number - 1 "
            + "where id = #{id} and user_id = #{userId} and number > 0")
    void decrementNumber(@Param("id") Long id, @Param("userId") Long userId);

    @Delete("delete from shopping_cart where id = #{id} and user_id = #{userId} and number = 0")
    void deleteIfEmpty(@Param("id") Long id, @Param("userId") Long userId);

    @Insert("insert into shopping_cart "
            + "(name, user_id, dish_id, setmeal_id, dish_flavor, number, amount, image, create_time) "
            + "values (#{name}, #{userId}, #{dishId}, #{setmealId}, #{dishFlavor}, "
            + "#{number}, #{amount}, #{image}, #{createTime})")
    void insert(ShoppingCart shoppingCart);

    @Delete("delete from shopping_cart where user_id = #{userId}")
    void deleteByUserId(@Param("userId") Long userId);
}
