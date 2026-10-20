package com.sky.service.impl;

import java.util.ArrayList;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.exception.BaseException;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.result.PageResult;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DishServiceImpl implements DishService {

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private DishFlavorMapper dishFlavorMapper;

    @Autowired
    private SetmealDishMapper setmealDishMapper;

    @Override
    @Transactional
    public void saveWithFlavor(DishDTO dishDTO) {
        // 1. 表单中的菜品属性复制到实体
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);

        // 接口文档允许省略 status，未传时沿用数据库定义的起售默认值
        if (dish.getStatus() == null) {
            dish.setStatus(StatusConstant.ENABLE);
        }

        // 2. 插入菜品，公共字段由切面填写，主键由 MyBatis 回填
        dishMapper.insert(dish);
        Long dishId = dish.getId();

        // 3. 有口味时，填写新菜品 ID，再批量插入
        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && !flavors.isEmpty()) {
            for (DishFlavor flavor : flavors) {
                flavor.setDishId(dishId);
            }
            dishFlavorMapper.insertBatch(flavors);
        }
    }

    @Override 
    public PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO) {
        PageHelper.startPage(dishPageQueryDTO.getPage(), dishPageQueryDTO.getPageSize());
        Page<DishVO> page = dishMapper.pageQuery(dishPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    @Override
    @Transactional
    public void deleteBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()
                || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new DeletionNotAllowedException("请选择有效的菜品 ID");
        }

        // 先检查整批菜品，不在这个循环中删除。
        for (Long id : ids) {
            Dish dish = dishMapper.getById(id);
            if (dish == null) {
                throw new DeletionNotAllowedException("菜品不存在，无法删除");
            }
            if (StatusConstant.ENABLE.equals(dish.getStatus())) {
                throw new DeletionNotAllowedException(MessageConstant.DISH_ON_SALE);
            }
        }
        List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(ids);
        if (setmealIds != null && !setmealIds.isEmpty()) {
            throw new DeletionNotAllowedException(MessageConstant.DISH_BE_RELATED_BY_SETMEAL);
        }

        // 全部校验通过后，用两条 SQL 删除菜品及对应口味。
        dishMapper.deleteByIds(ids);
        dishFlavorMapper.deleteByDishIds(ids);
    }

    @Override
    public DishVO getByIdWithFlavor(Long id) {
        if (id == null || id <= 0) {
            throw new BaseException("请提供有效的菜品 ID");
        }
        Dish dish = dishMapper.getById(id);
        if (dish == null) {
            throw new BaseException("菜品不存在");
        }
        List<DishFlavor> flavors = dishFlavorMapper.getByDishId(id);
        DishVO dishVO = new DishVO();
        BeanUtils.copyProperties(dish, dishVO);
        dishVO.setFlavors(flavors);
        return dishVO;
    }

    @Override
    @Transactional
    public void updateWithFlavor(DishDTO dishDTO) {
        if (dishDTO == null || dishDTO.getId() == null || dishDTO.getId() <= 0) {
            throw new BaseException("请提供有效的菜品 ID");
        }
        Long dishId = dishDTO.getId();
        if (dishMapper.getById(dishId) == null) {
            throw new BaseException("菜品不存在");
        }

        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);
        dishMapper.update(dish);

        // 无论新口味是否为空，都先删除原有口味。
        dishFlavorMapper.deleteByDishIds(List.of(dishId));
        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && !flavors.isEmpty()) {
            for (DishFlavor flavor : flavors) {
                flavor.setDishId(dishId);
            }
            dishFlavorMapper.insertBatch(flavors);
        }
    }

    @Override
    public List<Dish> list(Long categoryId) {
        if (categoryId == null || categoryId <= 0) {
            throw new BaseException("请提供有效的菜品分类 ID");
        }
        Dish query = Dish.builder().categoryId(categoryId)
                .status(StatusConstant.ENABLE).build();
        return dishMapper.list(query);
    }

    // Day06 新增：保留此前管理端的 list(Long categoryId)。
    @Override
    public List<DishVO> listWithFlavor(Dish dish) {
        List<DishVO> result = new ArrayList<>();
        for (Dish item : dishMapper.list(dish)) {
            DishVO vo = new DishVO();
            BeanUtils.copyProperties(item, vo);
            vo.setFlavors(dishFlavorMapper.getByDishId(item.getId()));
            result.add(vo);
        }
        return result;
    }
}
