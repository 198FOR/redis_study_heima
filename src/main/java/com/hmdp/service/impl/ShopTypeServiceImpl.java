package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 查缓存
        List<String> cacheList = stringRedisTemplate.opsForList().range("cache:shop:type", 0, -1);
        ArrayList<ShopType> shopTypes = new ArrayList<>();

        // 缓存中存在，直接返回
        if (cacheList.size() > 0) {
            for (String str : cacheList) {
                ShopType shopType = JSONUtil.toBean(str,ShopType.class);
                shopTypes.add(shopType);
            }
            return Result.ok(shopTypes);
        }

        // 不存在，查本地
        LambdaQueryWrapper<ShopType> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.orderByAsc(ShopType::getSort);
        List<ShopType> typeList = list(queryWrapper);


        // 本地存在，返回，并写入缓存
        if (ObjectUtil.isNotNull(typeList)) {
            for (ShopType shopType : typeList) {
                String jsonStr = JSONUtil.toJsonStr(shopType);
                cacheList.add(jsonStr);
            }
            stringRedisTemplate.opsForList().rightPushAll("cache:shop:type",cacheList);
            return Result.ok(typeList);
        }

        // 本地不存在，返回错误
        return Result.fail("不存在该类数据");
    }
}
