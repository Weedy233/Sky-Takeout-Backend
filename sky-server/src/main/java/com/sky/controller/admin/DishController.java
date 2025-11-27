package com.sky.controller.admin;

import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;


/**
 * 菜品管理
 */
@RestController
@RequestMapping("/admin/dish")
@Api(tags = "菜品相关接口")
@Slf4j
public class DishController {

    @Autowired
    private DishService dishService;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final static String REDIS_DISH_PREFIX = "dish_category_";

    /**
     * 新增菜品
     */
    @PostMapping
    @CacheEvict(cacheNames = "setmealCache", key = "#dishDTO.categoryId")
    @ApiOperation("新增菜品")
    public Result<?> save(@RequestBody DishDTO dishDTO) {
        log.info("新建菜品：{}", dishDTO);
        dishService.saveWithFlavor(dishDTO);
        String pat = REDIS_DISH_PREFIX + dishDTO.getCategoryId();
        redisTemplate.delete(pat);
        return Result.success();
    }

    /**
     * 分页查询菜品
     */
    @GetMapping("/page")
    @ApiOperation("菜品分页查询")
    public Result<PageResult> pageQuery(DishPageQueryDTO dishPageQueryDTO) {
        log.info("分页查询菜品：{}", dishPageQueryDTO);
        PageResult result = dishService.pageQuery(dishPageQueryDTO);
        return Result.success(result);
    }

    /**
     * 批量删除菜品
     */
    @DeleteMapping
    @CacheEvict(cacheNames = "setmealCache", allEntries = true)
    @ApiOperation("批量删除菜品")
    public Result<?> delete(@RequestParam List<Long> ids) {
        log.info("批量删除菜品：{}", ids);
        dishService.deleteBatch(ids);
        cleanCache(REDIS_DISH_PREFIX + "*");
        return Result.success();
    }

    /**
     * 根据 id 查询菜品
     */
    @GetMapping("/{id}")
    @ApiOperation("根据 id 查询菜品")
    public Result<DishVO> getById(@PathVariable Long id) {
        log.info("根据 ID 查询菜品：{}", id);
        DishVO dishVO = dishService.getById(id);
        return Result.success(dishVO);
    }


    /**
     * 修改菜品
     */
    @PutMapping
    @CacheEvict(cacheNames = "setmealCache", allEntries = true)
    @ApiOperation("修改菜品")
    public Result<?> update(@RequestBody DishDTO dishDTO) {
        log.info("修改菜品：{}", dishDTO);
        dishService.updateWithFlavor(dishDTO);
        cleanCache(REDIS_DISH_PREFIX + "*");
        return Result.success();
    }

    /**
     * 菜品起售停售
     */
    @PostMapping("/status/{status}")
    @CacheEvict(cacheNames = "setmealCache", allEntries = true)
    @ApiOperation("起售停售菜品")
    public Result<?> enableOrDisable(@PathVariable Integer status, Long id) {
        log.info("起售禁售菜品：{}{}", status, id);
        dishService.enableOrDisable(status, id);
        cleanCache(REDIS_DISH_PREFIX + "*");
        return Result.success();
    }

    /**
     * 查询分类下所有菜品
     */
    @GetMapping("/list")
    @ApiOperation("查询分类下所有菜品")
    public Result<List<Dish>> getDishByCategoryId(@RequestParam Long categoryId) {
        log.info("查找分类下的所有菜品：{}", categoryId);
        List<Dish> dishes = dishService.getDishByCategoryId(categoryId);
        return Result.success(dishes);
    }

    private void cleanCache(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        redisTemplate.delete(keys);
    }
}
