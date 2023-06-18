package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryShopById(Long id) {
        // 从redis中查找
        String shopStr = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

        // 判断是否存在
        if (StrUtil.isNotBlank(shopStr)) {
            Shop shopCache = JSONUtil.toBean(shopStr, Shop.class);
            return Result.ok(shopCache);
        }

        // 判断查到的是否为空值
        if (shopStr != null) {
            return Result.fail("未找到该商户信息");
        }


        // 获取互斥锁
        String key = "lock:shop:" + id;
        try {
            boolean lock = getLock(key);
            // 判断是否获取成功
            if (!lock) {
                // 获取失败，休眠并重试
                try {
                    Thread.sleep(1000);
                    return queryShopById(id);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } else {
                // 获取成功，再次查询缓存是否存在，如果存在则无需重建缓存
                // 从redis中查找
                String shopStr2 = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);

                // 判断是否存在
                if (StrUtil.isNotBlank(shopStr2)) {
                    Shop shopCache = JSONUtil.toBean(shopStr, Shop.class);
                    return Result.ok(shopCache);
                }

                // 判断查到的是否为空值
                if (shopStr2 != null) {
                    return Result.fail("未找到该商户信息");
                }

                // 根据id查数据库
                Shop shopLocal = this.getById(id);
                if (ObjectUtil.isNotNull(shopLocal)) {
                    stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shopLocal), 60 * 60 * 24 * 7);
                    releaseLock(key);
                    return Result.ok(shopLocal);
                }
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", 60 * 2);
                releaseLock(key);
                return Result.fail("未找到该商户信息");
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            releaseLock(key);
        }

        return Result.fail("系统繁忙，请稍后再试");
    }

    // 获取锁
    private boolean getLock(String key) {
        Boolean flage = stringRedisTemplate.opsForValue().setIfAbsent(key, "lock", 10, TimeUnit.SECONDS);
        // 拆箱可能返回null
        // 只有当flag为true时，才是true，flag为false和null时，都是false
        return BooleanUtil.isTrue(flage);
    }

    // 释放锁
    private void releaseLock(String key) {
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateShop(Shop shop) {
        if (ObjectUtil.isNull(shop)) {
            return Boolean.FALSE;
        }
        // 更新数据库
        this.updateById(shop);

        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Boolean.TRUE;
    }

    private void saveShop2Redis(Long id) {
        // 查询店铺数据
        Shop shop = getById(id);

        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(60 * 60));

        // 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));

    }
}
