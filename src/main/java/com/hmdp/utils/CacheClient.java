package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit; // 1. 必须是用这个包
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 2. 参数改为标准的 TimeUnit
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    // 2. 参数改为标准的 TimeUnit
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        // 3. 修正：用实例对象调用 setData (不是 setDate，也不是用类名调用)
        redisData.setData(value);
        // 3. 修正：用实例对象调用 setExpireTime
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 2. 参数改为标准的 TimeUnit
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            // 4. 修正：使用泛型 R，而不是写死为 Shop
            return JSONUtil.toBean(json, type);
        }

        // 判断是否为空值 (解决缓存穿透)
        if (json != null) { // hutool的isNotBlank排除了null和""，这里直接判不为null即为空字符串
            return null;
        }

        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);

        // 5.不存在，返回错误
        // 4. 修正：判断 r 是否为空，而不是 shop
        if (r == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 6.存在，写入redis
        this.set(key, r, time, unit);

        // 7.返回
        // 4. 修正：返回 r
        return r;
    }

    public <R> r queryWithLogicalExpire(String keyPrefix,Long id,Class<R> type，Function <ID,R> dbFallback,, Long time, TimeUnit unit){
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        if (StrUtil.isBlank(json)){
            // 3.不存在，返回null
            return null;
        }

        // 4.不存在，根据id查询数据库
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            // 5.1未过期，返回商铺信息
            return r;
        }


        // 5.2已过期，需要缓存重建、

        // 6.缓存重建
        // 6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2判断是否获取成功
        if (isLock) {
            // 检查完缓存是空，准备去抢锁的那一瞬间，前一个持有锁的线程刚好重建完缓存并释放了锁。
            // 这时抢到了锁，如果不进行二次检查，就会再查一次数据库，导致多余的数据库压力
            // 所以获取锁成功后，再次检测缓存是否存在 (Double Check)
            String newShopJson = stringRedisTemplate.opsForValue().get(key);
            // 再次判断是否存在
            if (StrUtil.isNotBlank(newShopJson)){
                // 反序列化
                RedisData newRedisData = JSONUtil.toBean(newShopJson, RedisData.class);
                LocalDateTime newExpireTime = newRedisData.getExpireTime();
                // 判断是否过期
                if (newExpireTime.isAfter(LocalDateTime.now())) {
                    // 如果已经未过期（被其他线程重建完了），则直接返回最新数据，无需再次重建
                    unlock(lockKey); // 记得释放锁
                    JSONObject newData = (JSONObject) newRedisData.getData();
                    return JSONUtil.toBean(newData, Shop.class);
                }
            }


            // 6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    // 写入redis
                    this.saveShop2Redis(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4返回过期的商铺信息

        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}