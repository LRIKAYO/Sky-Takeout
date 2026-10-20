package com.sky.controller.user;

import com.sky.entity.Category;
import com.sky.exception.BaseException;
import com.sky.result.Result;
import com.sky.service.CategoryService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("userCategoryController")
@RequestMapping("/user/category")
@Api(tags = "C端-分类浏览")
public class CategoryController {
    @Autowired
    private CategoryService categoryService;

    @GetMapping("/list")
    @ApiOperation("查询启用分类")
    public Result<List<Category>> list(@RequestParam(value = "type", required = false) Integer type) {
        if (type != null && type != 1 && type != 2) {
            throw new BaseException("分类类型只能为 1 或 2");
        }
        return Result.success(categoryService.list(type));
    }
}
