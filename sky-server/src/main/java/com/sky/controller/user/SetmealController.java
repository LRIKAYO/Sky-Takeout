package com.sky.controller.user;

import com.sky.constant.StatusConstant;
import com.sky.entity.Setmeal;
import com.sky.exception.BaseException;
import com.sky.result.Result;
import com.sky.service.SetmealService;
import com.sky.vo.DishItemVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("userSetmealController")
@RequestMapping("/user/setmeal")
@Api(tags = "C端-套餐浏览")
public class SetmealController {
    @Autowired
    private SetmealService setmealService;

    @GetMapping("/list")
    @ApiOperation("按分类查询起售套餐")
    public Result<List<Setmeal>> list(@RequestParam("categoryId") Long categoryId) {
        if (categoryId <= 0) {
            throw new BaseException("请提供有效的套餐分类 ID");
        }
        Setmeal query = Setmeal.builder().categoryId(categoryId).status(StatusConstant.ENABLE).build();
        return Result.success(setmealService.list(query));
    }

    @GetMapping("/dish/{id}")
    @ApiOperation("查询起售套餐包含的菜品")
    public Result<List<DishItemVO>> dishList(@PathVariable("id") Long id) {
        return Result.success(setmealService.getDishItemById(id));
    }
}
