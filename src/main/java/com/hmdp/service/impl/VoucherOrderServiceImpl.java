package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 秒杀优化23：基于 lua 脚本判断库存是否充足以及用户是否下过订单
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

/*    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }*/

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        // 直接把 Lua 代码写在这里，避开文件读取的所有坑
        SECKILL_SCRIPT.setScriptText(
                "local voucherId = ARGV[1]\n" +
                        "local userId = ARGV[2]\n" +
                        "local stockKey = 'seckill:stock:' .. voucherId\n" +
                        "local orderKey = 'seckill:order:' .. voucherId\n" +
                        "\n" +
                        "if (tonumber(redis.call('get', stockKey)) <= 0) then\n" +
                        "    return 1\n" +
                        "end\n" +
                        "\n" +
                        "if (redis.call('sismember', orderKey, userId) == 1) then\n" +
                        "    return 2\n" +
                        "end\n" +
                        "\n" +
                        "redis.call('incrby', stockKey, -1)\n" +
                        "redis.call('sadd', orderKey, userId)\n" +
                        "return 0"
        );
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 秒杀优化24：基于阻塞队列实现异步秒杀
     */
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();// 线程池

    // 任务要在类初始化之后马上执行，用spring提供的注解
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 这里是子线程，不是父线程，无法获取主线程 threadLocal 的 AOP 上下文
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder (voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }// 线程任务

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户（现在是多线程，没办法直接取，要通过voucherOrder取）
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        // 判断是否获取锁成功
        if (!isLock){
            // 获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        // 获取锁成功可能有异常，要try
        try {
            // 获取代理对象事务这里子线程没法获取，要把事务对象提前到主线程 seckillVoucher 获取
            // IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    /**
     * 秒杀优化23：基于 lua 脚本判断库存是否充足以及用户是否下过订单
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),userId.toString()
        );

        // 2. 判断结果是否为0
        int r = result.intValue();
        if (r != 0){
            // 2.1 不为0，无购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        /**
         * 秒杀24：基于阻塞队列实现异步秒杀
         */
        // 2.2 为0，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();

        // 2.3.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        // 2.4.用户id
        voucherOrder.setUserId(userId);

        // 2.5.代金券id
        voucherOrder.setVoucherId(voucherId);

        // 2.6.放入阻塞队列
        orderTasks.add(voucherOrder);

        // 3.1.主线程获取代理对象，然后让子线程拿到代理对象：①传到阻塞队列②放入成员变量，这里用①见147行
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 3.返回订单id
        return Result.ok(0);

    }

/*    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1。查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }

        // 3.判断描述是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已结束！");
        }

        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }


        *//**
         * 秒杀07：一人一单(对 userId 上锁)
         * 因为如果锁的粒度太大，会导致每个线程进来都会被锁住，现在的情况就是所有用户都公用这一把锁，串行执行，效率很低，
         * 我们现在要完成的业务是一人一单，所以这个锁，应该只加在单个用户上，用户标识可以用 userId
         *//*
        Long userId = UserHolder.getUser().getId();
        // 在方法外部加锁，保证事务提交才释放锁
        // intern()：将toString生成的新字符串入池（常量池），池中无则添加、有则复用，确保同userId对应唯一字符串对象

//        synchronized (userId.toString().intern()){
//            // 获取代理对象（）事务
//            // 事务这个功能是代理对象提供的，直接this.方法是没有事务，所以要获取代理对象去调用方法
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }

        *//**
         * 分布锁11：实现分布锁1
         * 同一个用户才要锁的限制，锁的范围是用户，这里要拼接用户Id，一起作为锁的对象
         *//*
        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        *//**
         * 分布式锁18：引入redisson分布式锁框架
         *//*
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        // boolean isLock = lock.tryLock(1200); // stringRedisTemplate版本
        boolean isLock = lock.tryLock();
        // 判断是否获取锁成功
        if (!isLock){
            // 获取锁失败，返回错误
            return Result.fail("不允许重复下单");
        }
        // 获取锁成功可能有异常，要try
        try {
            // 获取代理对象（）事务
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁
            lock.unlock();
        }

    }*/



    /**
     * 秒杀优化24：基于阻塞队列实现异步秒杀
     */
    @Transactional
    // public Result createVoucherOrder(VoucherOrder voucherOrder)
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        /**
         * 秒杀07：一人一单
         */
        // 5.一人一单
        // 用户id
        // Long userId = UserHolder.getUser().getId();
        Long userId = voucherOrder.getUserId();
        // 5.1.查询订单
        // int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder).count();
        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户购买过
            log.error("用户已经购买了");
            return ;
        }

        // 6.扣减库存
        // boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherOrder);
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                /**
                 * 秒杀06：解决超卖问题(乐观锁解决不同用户)
                 * 原本使用乐观锁，先查询，修改之前判断当前状态和查询状态是否一致，where stock = ?
                 * 但是成功率太低了，高并发下只要库存被修改，其他请求就失效，所以只需判断stock>0，
                 */
                .gt("stock",0)
                .update();
        if(!success){
            // 扣减失败
            log.error("库存不足");
            return;
        }

/*        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();

        // 7.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        // 7.2.用户id
        voucherOrder.setUserId(userId);

        // 7.3.代金券id
        voucherOrder.setVoucherId(voucherOrder);
        save(voucherOrder);

        // 8.返回订单id
        return Result.ok(orderId);*/

        // 7.创建订单
        save(voucherOrder);
    }
}
