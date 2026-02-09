package com.hmdp;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.io.FileUtil;
import cn.hutool.cron.timingwheel.SystemTimer;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;

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

    @Resource
    private IUserService iUserService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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


    /**
     * 秒杀优化22：测试高并发1000个用户抢单
     *
     * 生成 1000 个模拟登录的 token，存到 Redis 里（让系统认为这些用户已登录），
     * 再把 token 写到 txt 文件里，供 JMeter 测试高并发用
     */
    @Test
    void createTokensForJMeter() {
        // 1. 准备接收 Token 的文件路径 (直接写到 txt 里)
        String filePath = "D:\\Develop\\hm-dianping\\jmeter\\tokens.txt";
        // 清理旧文件
        FileUtil.del(filePath);

        // 2. 从数据库里拿前 1000 个用户
        // 直接循环构造 user 数据存入 Redis
        List<String> tokenList = new ArrayList<>();

        for (int i = 1; i <= 1000; i++) {
            // 模拟用户 ID
            // 如果只是测抢单，Redis 里有 User 对象通常就够了
            User user = new User();
            user.setId((long) i);
            user.setNickName("user_" + i);

            // 3. 生成 Token
            String token = UUID.randomUUID().toString(true);

            // 4. 也就是 LoginInterceptor 需要的逻辑：把 User 转 Map 存 Redis
            // 参考 UserServiceImpl 的 login 逻辑
            String tokenKey = RedisConstants.LOGIN_USER_KEY + token;

            Map<String, Object> userMap = BeanUtil.beanToMap(user, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) ->
                                    fieldValue == null ? null : fieldValue.toString()));

            // 存入 Redis (模拟登录状态)
            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

            // 5. 收集 Token
            tokenList.add(token);
        }

        // 6. 写出到文件 (一行一个)
        FileUtil.writeUtf8Lines(tokenList, filePath);

        System.out.println("1000个token已生成，文件路径：" + filePath);
    }

}
