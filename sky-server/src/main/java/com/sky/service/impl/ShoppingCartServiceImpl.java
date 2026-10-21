package com.sky.service.impl;

import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.ShoppingCartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ShoppingCartServiceImpl implements ShoppingCartService {
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private DishMapper dishMapper;
    @Autowired
    private SetmealMapper setmealMapper;

    @Override
    @Transactional
    public void addShoppingCart(ShoppingCartDTO dto) {
        ShoppingCart cart = cartQuery(dto);
        Long userId = cart.getUserId();
        boolean isDish = dto.getDishId() != null;
        Long productId = isDish ? dto.getDishId() : dto.getSetmealId();

        if (isDish) {
            Dish dish = dishMapper.getById(productId);
            if (dish == null || !StatusConstant.ENABLE.equals(dish.getStatus())) {
                throw new ShoppingCartBusinessException("菜品不存在或已停售");
            }
            cart.setName(dish.getName());
            cart.setImage(dish.getImage());
            cart.setAmount(dish.getPrice());
        } else {
            Setmeal setmeal = setmealMapper.getById(productId);
            if (setmeal == null || !StatusConstant.ENABLE.equals(setmeal.getStatus())) {
                throw new ShoppingCartBusinessException("套餐不存在或已停售");
            }
            cart.setName(setmeal.getName());
            cart.setImage(setmeal.getImage());
            cart.setAmount(setmeal.getPrice());
        }

        List<ShoppingCart> rows = shoppingCartMapper.list(cart);
        if (rows.size() > 1) {
            throw new ShoppingCartBusinessException("购物车存在重复商品，请先核查数据");
        }
        if (!rows.isEmpty()) {
            shoppingCartMapper.incrementNumber(rows.get(0).getId(), userId);
            return;
        }
        cart.setNumber(1);
        cart.setCreateTime(LocalDateTime.now());
        shoppingCartMapper.insert(cart);
    }

    @Override
    @Transactional
    public void subShoppingCart(ShoppingCartDTO dto) {
        ShoppingCart query = cartQuery(dto);
        List<ShoppingCart> rows = shoppingCartMapper.list(query);
        if (rows.size() > 1) {
            throw new ShoppingCartBusinessException("购物车存在重复商品，请先核查数据");
        }
        if (rows.isEmpty()) {
            return;
        }
        Long id = rows.get(0).getId();
        shoppingCartMapper.decrementNumber(id, query.getUserId());
        shoppingCartMapper.deleteIfEmpty(id, query.getUserId());
    }

    @Override
    public List<ShoppingCart> showShoppingCart() {
        return shoppingCartMapper.list(ShoppingCart.builder()
                .userId(requireCurrentUser()).build());
    }

    @Override
    public void cleanShoppingCart() {
        shoppingCartMapper.deleteByUserId(requireCurrentUser());
    }

    private ShoppingCart cartQuery(ShoppingCartDTO dto) {
        Long userId = requireCurrentUser();
        if (dto == null || (dto.getDishId() == null) == (dto.getSetmealId() == null)) {
            throw new ShoppingCartBusinessException("菜品 ID 与套餐 ID 必须二选一");
        }
        boolean isDish = dto.getDishId() != null;
        Long productId = isDish ? dto.getDishId() : dto.getSetmealId();
        if (productId <= 0) {
            throw new ShoppingCartBusinessException("商品 ID 必须大于 0");
        }
        String flavor = dto.getDishFlavor() == null ? "" : dto.getDishFlavor().trim();
        if ((!isDish && !flavor.isEmpty()) || flavor.length() > 50) {
            throw new ShoppingCartBusinessException("套餐不支持口味，菜品口味不能超过 50 个字符");
        }
        return ShoppingCart.builder().userId(userId).dishId(dto.getDishId())
                .setmealId(dto.getSetmealId()).dishFlavor(flavor).build();
    }

    private Long requireCurrentUser() {
        Long userId = BaseContext.getCurrentId();
        if (userId == null || userId <= 0) {
            throw new ShoppingCartBusinessException("请先登录");
        }
        return userId;
    }
}
