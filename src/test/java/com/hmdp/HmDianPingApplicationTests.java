package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Test
//    void testSaveShop(){
//        // 数值不加L默认是int类型 没法装箱成Long
//        shopService.saveShop2Redis(1L,10L);
//    }

        void testSaveShop() throws InterruptedException{
        // 数值不加L默认是int类型 没法装箱成Long
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L,shop,10L, TimeUnit.SECONDS);
    }

}
