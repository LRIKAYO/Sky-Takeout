package com.sky.mapper;

import com.github.pagehelper.Page;
import com.sky.annotation.AutoFill;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Setmeal;
import com.sky.enumeration.OperationType;
import com.sky.vo.SetmealVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SetmealMapper {

    // 保留原有分类删除校验使用的方法。
    @Select("select count(id) from setmeal where category_id = #{categoryId}")
    Integer countByCategoryId(@Param("categoryId") Long categoryId);

    @AutoFill(OperationType.INSERT)
    void insert(Setmeal setmeal);

    Page<SetmealVO> pageQuery(SetmealPageQueryDTO query);

    @Select("select * from setmeal where id = #{id}")
    Setmeal getById(@Param("id") Long id);

    @AutoFill(OperationType.UPDATE)
    void update(Setmeal setmeal);

    void deleteByIds(@Param("ids") List<Long> ids);
}