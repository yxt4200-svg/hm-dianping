package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import cn.hutool.core.util.IdUtil;
import org.springframework.data.redis.core.script.DefaultReactiveScriptExecutor;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 一人一单
 */
public class SimpleRedisLock implements ILock  {

    private String name; // 锁的名字
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:"; // 锁的前缀

    // 1.服务器专属门牌（UUID）：解决多JVM线程号重复问题，一台服务器一个唯一门牌
    private static final String ID_PREFIX = IdUtil.simpleUUID() + "-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // value可以是线程名也可以是线程号，只要能标识是谁抢的锁就行
        // 2. 生成当前线程的唯一标识：服务器门牌 + 线程号（比如abc123-101）
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 3.获取锁
        // setIfAbsent：还没人占锁，我就占，并且给锁设个过期时间；如果已经被人占了，我就抢不到
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId , timeoutSec, TimeUnit.SECONDS);
        // return success; 错误，自动拆箱若为null会空指针异常
        // 4.安全返回抢锁结果
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        /**
         * 分布式锁16：调用lua脚本改造分布式锁满足原子性
         */
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(
                KEY_PREFIX + name),ID_PREFIX + Thread.currentThread().getId());
    }

//    @Override
//    public void unlock() {
//        // 1.生成自己的线程唯一标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        // 2. 去Redis查账本：查这把锁当前的占用者是谁（存的是抢锁时的线程标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        // 3.核对身份
//        if(threadId.equals(id)) {
//            // 释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
