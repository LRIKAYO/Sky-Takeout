package com.sky.service.impl;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.SetmealDish;
import com.sky.exception.BaseException;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.exception.SetmealEnableFailedException;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.SetmealService;
import com.sky.vo.SetmealVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class SetmealServiceImpl implements SetmealService {

    @Autowired
    private SetmealMapper setmealMapper;

    @Autowired
    private SetmealDishMapper setmealDishMapper;

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Override
    @Transactional
    public void saveWithDish(SetmealDTO dto) {
        List<SetmealDish> dishes = validateAndBuildDishes(dto, false);
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(dto, setmeal);
        // 新增主键由数据库生成；课程要求默认停售，不能使用数据库默认的 1。
        setmeal.setId(null);
        setmeal.setStatus(StatusConstant.DISABLE);
        setmealMapper.insert(setmeal);
        for (SetmealDish dish : dishes) {
            dish.setSetmealId(setmeal.getId());
        }
        setmealDishMapper.insertBatch(dishes);
    }

    @Override
    public PageResult pageQuery(SetmealPageQueryDTO query) {
        if (query == null || query.getPage() < 1 || query.getPageSize() < 1) {
            throw new BaseException("页码和每页条数必须大于 0");
        }
        if (query.getStatus() != null) {
            checkStatus(query.getStatus());
        }
        // 紧跟分页 SQL，不在这两句之间增加其他数据库查询。
        PageHelper.startPage(query.getPage(), query.getPageSize());
        Page<SetmealVO> page = setmealMapper.pageQuery(query);
        return new PageResult(page.getTotal(), page.getResult());
    }

    @Override
    public SetmealVO getByIdWithDish(Long id) {
        Setmeal setmeal = requireSetmeal(id);
        SetmealVO vo = new SetmealVO();
        BeanUtils.copyProperties(setmeal, vo);
        vo.setSetmealDishes(setmealDishMapper.getBySetmealId(id));
        return vo;
    }

    @Override
    @Transactional
    public void update(SetmealDTO dto) {
        if (dto == null) {
            throw new BaseException("请提供套餐信息");
        }
        Setmeal original = requireSetmeal(dto.getId());
        Integer status = dto.getStatus() == null ? original.getStatus() : dto.getStatus();
        checkStatus(status);
        // 保持起售状态编辑时，也不能换入停售菜品。
        List<SetmealDish> dishes = validateAndBuildDishes(dto, StatusConstant.ENABLE.equals(status));
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(dto, setmeal);
        setmeal.setStatus(status);
        setmealMapper.update(setmeal);
        setmealDishMapper.deleteBySetmealId(setmeal.getId());
        for (SetmealDish dish : dishes) {
            dish.setSetmealId(setmeal.getId());
        }
        setmealDishMapper.insertBatch(dishes);
    }

    @Override
    @Transactional
    public void startOrStop(Integer status, Long id) {
        checkStatus(status);
        requireSetmeal(id);
        if (StatusConstant.ENABLE.equals(status)) {
            List<SetmealDish> relations = setmealDishMapper.getBySetmealId(id);
            if (relations.isEmpty()) {
                throw new SetmealEnableFailedException("套餐不包含菜品，无法起售");
            }
            for (SetmealDish relation : relations) {
                Dish dish = dishMapper.getById(relation.getDishId());
                if (dish == null || !StatusConstant.ENABLE.equals(dish.getStatus())) {
                    throw new SetmealEnableFailedException(MessageConstant.SETMEAL_ENABLE_FAILED);
                }
            }
        }
        setmealMapper.update(Setmeal.builder().id(id).status(status).build());
    }

    @Override
    @Transactional
    public void deleteBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()
                || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new DeletionNotAllowedException("请选择有效的套餐 ID");
        }
        List<Long> uniqueIds = new ArrayList<>(new LinkedHashSet<>(ids));
        // 检查完整批次之后才执行删除，避免起售套餐导致部分删除。
        for (Long id : uniqueIds) {
            Setmeal setmeal = requireSetmeal(id);
            if (StatusConstant.ENABLE.equals(setmeal.getStatus())) {
                throw new DeletionNotAllowedException(MessageConstant.SETMEAL_ON_SALE);
            }
        }
        setmealDishMapper.deleteBySetmealIds(uniqueIds);
        setmealMapper.deleteByIds(uniqueIds);
    }

    private Setmeal requireSetmeal(Long id) {
        if (id == null || id <= 0) {
            throw new BaseException("请提供有效的套餐 ID");
        }
        Setmeal setmeal = setmealMapper.getById(id);
        if (setmeal == null) {
            throw new BaseException("套餐不存在");
        }
        return setmeal;
    }

    private void checkStatus(Integer status) {
        if (!StatusConstant.ENABLE.equals(status) && !StatusConstant.DISABLE.equals(status)) {
            throw new BaseException("套餐状态只能为 0 或 1");
        }
    }

    private List<SetmealDish> validateAndBuildDishes(SetmealDTO dto, boolean mustBeOnSale) {
        if (dto == null || !StringUtils.hasText(dto.getName()) || dto.getName().length() > 32) {
            throw new BaseException("套餐名称不能为空，且不能超过 32 个字符");
        }
        BigDecimal price = dto.getPrice();
        if (price == null || price.signum() < 0
                || price.compareTo(new BigDecimal("99999999.99")) > 0
                || price.stripTrailingZeros().scale() > 2) {
            throw new BaseException("套餐价格必须是范围内的非负金额，最多两位小数");
        }
        if (!StringUtils.hasText(dto.getImage()) || dto.getImage().length() > 255) {
            throw new BaseException("请提供有效的套餐图片，地址不能超过 255 个字符");
        }
        if (dto.getDescription() != null && dto.getDescription().length() > 255) {
            throw new BaseException("套餐描述不能超过 255 个字符");
        }
        // 复用已有分类查询：它只返回启用分类；2 代表套餐分类。
        if (dto.getCategoryId() == null || categoryMapper.list(2).stream()
                .noneMatch(category -> dto.getCategoryId().equals(category.getId()))) {
            throw new BaseException("请选择有效且启用的套餐分类");
        }
        List<SetmealDish> input = dto.getSetmealDishes();
        if (input == null || input.isEmpty()) {
            throw new BaseException("套餐必须包含菜品");
        }
        Set<Long> seen = new HashSet<>();
        List<SetmealDish> result = new ArrayList<>();
        for (SetmealDish item : input) {
            if (item == null || item.getDishId() == null || item.getDishId() <= 0
                    || item.getCopies() == null || item.getCopies() <= 0) {
                throw new BaseException("菜品 ID 必须有效，份数必须是正整数");
            }
            if (!seen.add(item.getDishId())) {
                throw new BaseException("同一菜品请合并份数，不要重复添加");
            }
            Dish dish = dishMapper.getById(item.getDishId());
            if (dish == null) {
                throw new BaseException("套餐中的菜品不存在");
            }
            if (mustBeOnSale && !StatusConstant.ENABLE.equals(dish.getStatus())) {
                throw new SetmealEnableFailedException(MessageConstant.SETMEAL_ENABLE_FAILED);
            }
            // ID、归属及冗余字段由服务端确定，不使用客户端伪造的数据。
            result.add(SetmealDish.builder().dishId(dish.getId()).name(dish.getName())
                    .price(dish.getPrice()).copies(item.getCopies()).build());
        }
        return result;
    }
}