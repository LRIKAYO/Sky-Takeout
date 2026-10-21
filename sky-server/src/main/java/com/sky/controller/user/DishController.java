package com.sky.controller.user;

import com.sky.constant.StatusConstant;
import com.sky.entity.Dish;
import com.sky.exception.BaseException;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("userDishController")
@RequestMapping("/user/dish")
@Api(tags = "C端-菜品浏览")
public class DishController {
    @Autowired
    private DishService dishService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @GetMapping("/list")
    @ApiOperation("按分类查询起售菜品及口味")
    @SuppressWarnings("unchecked")
    public Result<List<DishVO>> list(@RequestParam("categoryId") Long categoryId) {
        if (categoryId <= 0) {
            throw new BaseException("请提供有效的菜品分类 ID");
        }
        String key = "dish_" + categoryId;
        List<DishVO> list = (List<DishVO>) redisTemplate.opsForValue().get(key);
        if (list != null) {
            return Result.success(list);
        }
        Dish query = Dish.builder().categoryId(categoryId).status(StatusConstant.ENABLE).build();
        list = dishService.listWithFlavor(query);
        redisTemplate.opsForValue().set(key, list);
        return Result.success(list);
    }
}
