package com.sky.service.impl;

import com.sky.dto.DishDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.exception.BaseException;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.vo.DishVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class DishServiceImplEditTest {

    @Test
    void returnsDetailsAndReplacesOrClearsOnlyTheTargetDishesFlavors() {
        DishMapper dishes = mock(DishMapper.class);
        DishFlavorMapper flavors = mock(DishFlavorMapper.class);
        DishServiceImpl service = new DishServiceImpl();
        ReflectionTestUtils.setField(service, "dishMapper", dishes);
        ReflectionTestUtils.setField(service, "dishFlavorMapper", flavors);
        Dish existing = new Dish();
        existing.setId(9001L);
        existing.setName("temporary dish");
        existing.setPrice(new BigDecimal("6.00"));
        existing.setStatus(0);
        when(dishes.getById(9001L)).thenReturn(existing);
        DishFlavor old = new DishFlavor();
        old.setDishId(9001L);
        old.setName("spice");
        old.setValue("[\"mild\"]");
        when(flavors.getByDishId(9001L)).thenReturn(List.of(old));

        DishVO detail = service.getByIdWithFlavor(9001L);
        assertEquals(existing.getName(), detail.getName());
        assertEquals(existing.getPrice(), detail.getPrice());
        assertEquals(List.of(old), detail.getFlavors());
        when(flavors.getByDishId(9001L)).thenReturn(List.of());
        assertTrue(service.getByIdWithFlavor(9001L).getFlavors().isEmpty());
        verify(dishes, never()).update(any());
        verify(flavors, times(2)).getByDishId(9001L);
        verifyNoMoreInteractions(flavors);

        DishDTO request = new DishDTO();
        request.setId(9001L);
        request.setName("renamed dish");
        request.setPrice(new BigDecimal("8.00"));
        DishFlavor added = new DishFlavor();
        added.setDishId(9999L); // A client-supplied unrelated ID must not be used.
        added.setName("sweetness");
        added.setValue("[\"less sugar\"]");
        request.setFlavors(List.of(added));
        service.updateWithFlavor(request);
        ArgumentCaptor<Dish> written = ArgumentCaptor.forClass(Dish.class);
        verify(dishes).update(written.capture());
        assertEquals(9001L, written.getValue().getId());
        assertEquals(request.getPrice(), written.getValue().getPrice());
        assertNull(written.getValue().getStatus());
        assertEquals(9001L, added.getDishId());
        verify(flavors).deleteByDishIds(List.of(9001L));
        verify(flavors).insertBatch(request.getFlavors());

        for (boolean explicitNull : List.of(false, true)) {
            clearInvocations(dishes, flavors);
            request.setFlavors(explicitNull ? null : List.of());
            service.updateWithFlavor(request);
            verify(flavors).deleteByDishIds(List.of(9001L));
            verify(flavors, never()).insertBatch(anyList());
        }

        clearInvocations(dishes, flavors);
        assertThrows(BaseException.class, () -> service.getByIdWithFlavor(0L));
        assertThrows(BaseException.class, () -> service.getByIdWithFlavor(9002L));
        assertThrows(BaseException.class, () -> service.updateWithFlavor(new DishDTO()));
        request.setId(9002L);
        assertThrows(BaseException.class, () -> service.updateWithFlavor(request));
        verify(dishes, never()).update(any());
        verifyNoInteractions(flavors);
    }
}
