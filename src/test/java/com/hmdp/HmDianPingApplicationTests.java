package com.hmdp;

import cn.hutool.cron.timingwheel.SystemTimer;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    // 测试并发情况下生成id的性能和值的情况
    @Test
    void testIdWorker() throws InterruptedException{
        /**
         *  CountDownLatch
         *  需等300个任务（每个任务生成100个ID）全部完成，主线程再统计总耗时
         *  不加这个会在第300个任务刚开始生成第一个id就统计时间
         */
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++){
              long id = redisIdWorker.nextId("order");
              System.out.println("id = " + id);
          }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        // 任务提交300次，共3万个id
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }

        // await谁调用就是让谁暂停，要等CountDownLatch计数器为0，才能进行主线程
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));

    }


    // 缓存商店测试（解决击穿/穿透版本）
    @Test
    void testSaveShop2() {
        /**
         * 热点 key（如 ID=1 的网红店铺）如果首次被访问时缓存为空，会直接打穿到数据库（缓存击穿）；
         * 提前把数据存入 Redis，用户访问时直接查缓存，数据库就不会被海量请求压垮；
         * 用逻辑过期而非 Redis 原生过期：是为了在数据过期后，异步更新缓存，而非让所有请求瞬间打向数据库。
         */
        // 1. 先从数据库查询出店铺数据
        Shop shop = shopService.getById(1L);

        // 2. 如果数据库也没数据，就没必要存了
        if (shop == null) {
            System.out.println("店铺不存在，无法预热缓存！");
            return;
        }

        // 3. 调用 CacheClient 把数据写入 Redis，并设置逻辑过期时间为 10秒
        // 注意：这里的 key 必须和代码里查询时拼写的规则一致 (cache:shop: + id)
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);

        System.out.println("缓存预热成功！");
    }


    // 缓存商店测试
    @Test
    void testSaveShop() throws InterruptedException{
        // 数值不加L默认是int类型 没法装箱成Long
        shopService.saveShop2Redis(1L,10L);
    }


}
