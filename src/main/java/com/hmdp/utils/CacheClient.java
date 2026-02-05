package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.statement.select.KSQLWindow;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.hmdp.utils.RedisData;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    public void set(String key, Object value, Long time, KSQLWindow.TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, KSQLWindow.TimeUnit unit){
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        RedisData.setDate(value);
        RedisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 返回值不确定什么类型，用泛型
    // 泛型出错会在编译器报错，object出错就是运行时异常了
    // 泛型 = 类型安全 + 代码简洁 + 可读性高；Object = 类型不安全 + 代码冗余 + 易出错
    // prefix  n.前缀(缀于单词前以改变其意义的字母或字母组合); 前置代号(置于前面的单词或字母、数字); (人名前的)称谓;
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time, KSQLWindow.TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            // 3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson,type);
            return shop;
        }
        // 判断是否为空值
        if ("".equals(shopJson)){
            // 返回错误信息
            return null;
        }

        // 4.不存在，根据id查询数据库
        // 不知道查询什么类型的数据，交给调用者，把数据库查询的逻辑作为参数传进函数
        // 这个逻辑有参数id，有返回值R，有参有返回值的函数叫function
        R r = dbFallback.apply(id);

        // 5.不存在，返回错误
        if (shop == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 6.存在，写入redis，这里的时间应该是别人传进来的时间，不要写死了
        this.set(key,r,time,unit);

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

}
