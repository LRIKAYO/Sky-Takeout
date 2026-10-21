package com.sky.service;

import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.ShoppingCart;
import java.util.List;

public interface ShoppingCartService {
    void addShoppingCart(ShoppingCartDTO dto);
    void subShoppingCart(ShoppingCartDTO dto);
    List<ShoppingCart> showShoppingCart();
    void cleanShoppingCart();
}
