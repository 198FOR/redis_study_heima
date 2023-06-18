package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Created by sopt on 6/13/23 20:47.
 */
@Slf4j
@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    public void set(String key, Object value, long timeout, TimeUnit unit) {

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), timeout, unit);
    }


    public void setWithLogicalExpire(String key, Object value, Long timeout, TimeUnit unit) {
        // 逻辑过期
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(timeout)));
        redisData.setData(JSONUtil.toJsonStr(value));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), timeout, unit);
    }

    public <T, E> T queryWithPassThrough(String keyPrefix, E id, Class<T> clazz,
                                         Long timeout, TimeUnit unit,
                                         Function<E, T> dbFallback) {
        String key = keyPrefix + id;
        // 从redis中查找
        String json = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, clazz);
        }

        // 判断查到的是否为空值
        if (json != null) {
            return null;
        }


        // 不存在，则根据id查数据库
        T t = dbFallback.apply(id);

        // 不存在，返回错误
        if (t == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", 30, TimeUnit.SECONDS);
            // 返回错误信息
            return null;
        }

        // 存在，写入redis
        this.set(key, t, timeout, unit);
        return t;
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <T, ID> T queryWithLogicalExpire(String keyPrefix, ID id, Class<T> clazz,
                                            long timeout, TimeUnit unit,
                                            Function<ID, T> dbFallback) {
        String key = keyPrefix + id;
        // 1.从redis中查找
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.不存在，直接返回
            return null;
        }

        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        T t = JSONUtil.toBean((JSONObject) redisData.getData(), clazz);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (LocalDateTime.now().isAfter(expireTime)) {
            // 5.1未过期，直接返回数据
            return t;
        }

        // 5.2过期，需要缓存重建
        // 6.缓存重建
        // 6.1获取互斥锁
        String lockKey = "lock" + id;
        boolean isLock = getLock(lockKey);
        // 6.2判断是否获取到锁
        if (isLock) {
            // 6.3 成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {

                    // 6.3.1 从数据库中查询数据
                    T t1 = dbFallback.apply(id);
                    // 6.3.2 将数据写入redis
                    setWithLogicalExpire(key, t1, timeout, unit);
                } catch (Exception e) {
                    log.error("缓存重建失败", e);
                } finally {
                    // 6.3.3 释放锁
                    releaseLock(lockKey);
                }
            });
        }
        return t;
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
}
