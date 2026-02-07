package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Override
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


        /**
         * 秒杀07：一人一单(对 userId 上锁)
         * 因为如果锁的粒度太大，会导致每个线程进来都会被锁住，现在的情况就是所有用户都公用这一把锁，串行执行，效率很低，
         * 我们现在要完成的业务是一人一单，所以这个锁，应该只加在单个用户上，用户标识可以用 userId
         */
        Long userId = UserHolder.getUser().getId();
        // 在方法外部加锁，保证事务提交才释放锁
        // intern()：将toString生成的新字符串入池（常量池），池中无则添加、有则复用，确保同userId对应唯一字符串对象
        synchronized (userId.toString().intern()){
            // 获取代理对象（）事务
            // 事务这个功能是代理对象提供的，直接this.方法是没有事务，所以要获取代理对象去调用方法
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }

    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        /**
         * 秒杀07：一人一单
         */
        // 5.一人一单
        // 用户id
        Long userId = UserHolder.getUser().getId();
        // 5.1.查询订单
        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户购买过
            return Result.fail("用户已购买过一次！");
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                /**
                 * 秒杀06：解决超卖问题(乐观锁解决不同用户)
                 * 原本使用乐观锁，先查询，修改之前判断当前状态和查询状态是否一致，where stock = ?
                 * 但是成功率太低了，高并发下只要库存被修改，其他请求就失效，所以只需判断stock>0，
                 */
                .gt("stock",0)
                .update();
        if(!success){
            // 扣减失败
            return Result.fail("库存不足！");
        }

        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();

        // 7.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);

        // 7.2.用户id
        voucherOrder.setUserId(userId);

        // 7.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        // 8.返回订单id
        return Result.ok(orderId);
    }
}
