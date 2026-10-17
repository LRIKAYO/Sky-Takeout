package com.sky.service.impl;

import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.entity.Dish;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class DishServiceImplDeleteTest {

    @Test
    void validatesTheWholeBatchBeforeDeletingDishesAndFlavors() {
        DishMapper dishes = mock(DishMapper.class);
        DishFlavorMapper flavors = mock(DishFlavorMapper.class);
        SetmealDishMapper relations = mock(SetmealDishMapper.class);
        DishServiceImpl service = new DishServiceImpl();
        ReflectionTestUtils.setField(service, "dishMapper", dishes);
        ReflectionTestUtils.setField(service, "dishFlavorMapper", flavors);
        ReflectionTestUtils.setField(service, "setmealDishMapper", relations);
        List<Long> ids = List.of(9001L, 9002L);

        assertThrows(DeletionNotAllowedException.class, () -> service.deleteBatch(List.of()));
        assertThrows(DeletionNotAllowedException.class, () -> service.deleteBatch(List.of(0L)));
        verifyNoInteractions(dishes, flavors, relations);

        Dish stopped = new Dish();
        stopped.setStatus(StatusConstant.DISABLE);
        Dish selling = new Dish();
        selling.setStatus(StatusConstant.ENABLE);
        when(dishes.getById(9001L)).thenReturn(stopped);
        // The second ID does not exist: the first stopped dish must remain untouched.
        assertThrows(DeletionNotAllowedException.class, () -> service.deleteBatch(ids));
        verify(dishes, never()).deleteByIds(anyList());
        verifyNoInteractions(flavors, relations);

        when(dishes.getById(9002L)).thenReturn(selling);
        assertEquals(MessageConstant.DISH_ON_SALE,
                assertThrows(DeletionNotAllowedException.class, () -> service.deleteBatch(ids)).getMessage());
        verify(dishes, never()).deleteByIds(anyList());
        verifyNoInteractions(flavors, relations);

        when(dishes.getById(9002L)).thenReturn(stopped);
        when(relations.getSetmealIdsByDishIds(ids)).thenReturn(List.of(8001L));
        assertEquals(MessageConstant.DISH_BE_RELATED_BY_SETMEAL,
                assertThrows(DeletionNotAllowedException.class, () -> service.deleteBatch(ids)).getMessage());
        verify(dishes, never()).deleteByIds(anyList());
        verifyNoInteractions(flavors);

        when(relations.getSetmealIdsByDishIds(ids)).thenReturn(List.of());
        service.deleteBatch(ids);
        verify(dishes).deleteByIds(ids);
        verify(flavors).deleteByDishIds(ids);
    }
}
