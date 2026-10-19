package com.sky.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.sky.entity.SetmealDish;

import java.util.List;

@Mapper
public interface SetmealDishMapper {

    List<Long> getSetmealIdsByDishIds(@Param("dishIds") List<Long> dishIds);

    void insertBatch(@Param("setmealDishes") List<SetmealDish> setmealDishes);

    @Select("select * from setmeal_dish where setmeal_id = #{setmealId} order by id")
    List<SetmealDish> getBySetmealId(@Param("setmealId") Long setmealId);

    void deleteBySetmealId(@Param("setmealId") Long setmealId);

    void deleteBySetmealIds(@Param("ids") List<Long> ids);
}