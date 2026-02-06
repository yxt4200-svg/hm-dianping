package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L; // 2022.1.1 0:0:0

    /**
     * 序列号位数
     */
    private static final int COUNT_BITS = 32;

    /**
     * 序列号（32位）仅表示当日内生成ID的次数（按日期重置，从1开始）
     * 非全量时间内的累计次数，避免全局自增溢出
     * 虽然靠前面31位时间戳也能区分，但是区分程度大大降低了，要是一秒内下的订单超了呢（虽然不可能）
     */
    public static final Long COUNT_BIT = 32L;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    /**
     *  keyPrefix:基于redis自增长，根据不同业务不同的key对应的value不断自增
     *  所以要用前缀区分不同的业务，可以理解为业务的前缀
     */
    public long nextId(String keyPrefix){
        // 符号位不用管，只需保证它是正数就行

        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        /**
         * yyyy:MM:dd用冒号分隔开，redis会分层级，方便统计
         * 比如统计月用yyyy:MM前缀所有的key
         */
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长
        /**
         * key不存在不会空指针，每一天第一个订单，会自动创建key，＋1，并返回1
         */
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3.拼接并返回
        /**
         * 不能用字符串直接拼接，返回类型要为long
         * 用数字的形式拼接，时间戳是高位，序列号是低位，要用位运算
         * 逻辑：时间戳在低位左移32位变高位，自动补充0，再把序列号填充上去，用或运算（任何数与0或运算都是自身，效率比加法高）
         */
        return timestamp << COUNT_BITS | count;
    }

//    public static void main(String[] args) {
//        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
//        long second = time.toEpochSecond(ZoneOffset.UTC);
//        System.out.println("second = " + second); // 运行结果：second = 1640995200
//    }
}
