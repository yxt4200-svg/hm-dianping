package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.controller.ShopController;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
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
    public Result queryById(Long id) {
        // 缓存穿透
        // Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);

        // 逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,10L, TimeUnit.SECONDS);
        if(shop == null){
            return Result.fail("店铺不存在");
        }

        // 7.返回
        return Result.ok(shop);
    }

    // 线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 逻辑过期解决缓存击穿
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        if (StrUtil.isBlank(shopJson)){
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
            return shop;
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
                    // 重建缓存
                    // this指当前对象
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4返回过期的商铺信息

        return shop;
    }

    // 互斥锁解决缓存穿透
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            // 3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }

        // 判读命中的是否为空值
        // null不代表是"",而""是缓存穿透，而null代表缓存没有数据，要去数据库查
        // 不等于null就是等于空串
        // shopJson要么是""要么是null。是""说明以前查过，这是无效id；是null证明没查过，所以去查数据库
//        if (shopJson != null ){
//            // 返回错误信息
//            return Result.fail("店铺不存在！");
//        }

        // 下面更好理解
        // 判断是否为空值 (解决缓存穿透)
        if ("".equals(shopJson)){
            // 返回错误信息
            return null;
        }

        // 4.开始实现缓存重建
        // 4.1 获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2 判断是否获取成功
            if (!isLock){
                // 4.3 失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // 检查完缓存是空，准备去抢锁的那一瞬间，前一个持有锁的线程刚好重建完缓存并释放了锁。
            // 这时抢到了锁，如果不进行二次检查，就会再查一次数据库，导致多余的数据库压力
            // 所以获取锁成功后，再次检测缓存是否存在 (Double Check)
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 再次判断是否存在
            if (StrUtil.isNotBlank(shopJson)){
                // 如果存在，说明上一个线程已经重建好了，直接返回，无需查库
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 再次判断是否为空值 (防止并发穿透)
            if ("".equals(shopJson)){
                return null;
            }

            // 4，4 成功，根据id查询数据库
            shop = getById(id);

            // 模拟重建的延时
            Thread.sleep(200);

            // 5.不存在，返回错误
            if (shop == null) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 6.存在，写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7.释放互斥锁
            unlock(lockKey);
        }

        // 8.返回
        return shop;
    }

    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            // 3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
            return shop;
        }

        // 判读命中的是否为空值
        // null不代表是"",而""是缓存穿透，而null代表缓存没有数据，要去数据库查
        // 不等于null就是等于空串
        // shopJson要么是""要么是null。是""说明以前查过，这是无效id；是null证明没查过，所以去查数据库
//        if (shopJson != null ){
//            // 返回错误信息
//            return Result.fail("店铺不存在！");
//        }

        // 下面更好理解
        if ("".equals(shopJson)){
            // 返回错误信息
            return null;
        }

        // 4.不存在，根据id查询数据库
        Shop shop = getById(id);

        // 5.不存在，返回错误
        if (shop == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 6.存在，写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 7.返回
        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        Thread.sleep(200);
        // 1.查询店铺数据
        Shop shop = getById(id);
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        // 这个时间是用于给程序判断这个key是否已经过期的，因为这些热带key会在活动前提前存到Redis中
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData) );
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok(shop);
    }
}
