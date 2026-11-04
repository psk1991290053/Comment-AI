package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据优惠券id下单秒杀券
     * @param voucherId
     * @return
     */
    public Result orderSeckillVoucherById(Long voucherId) {
        //1.查询秒杀卷时间是否合法
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀还未开始!");
        }
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束!");
        }
        //2.查询秒杀券库存是否足够
        if (!(seckillVoucher.getStock() >= 1)) {
            return Result.fail("秒杀券已抢空!");
        }
        //3.一人一单 这个锁只是防止一人买多单的 对其他人来买的线程没有影响
        SimpleRedisLock lock = null;
        Long userId = UserHolder.getUser().getId();
        //3.1创建锁对象
        lock = new SimpleRedisLock(stringRedisTemplate, "seckill_voucher_order:" + userId);
        //3.2尝试创建锁
        Boolean isLock = lock.tryLock(5L);
        if(!isLock){
            return Result.fail("请勿重复点击!");
        }
        try {
            //6.创建订单并返回订单号
            VoucherOrderServiceImpl proxy = (VoucherOrderServiceImpl) AopContext.currentProxy();
            return proxy.createOrder(voucherId);
        } finally {
            lock.unLock();
        }
    }

    @Transactional
    public Result createOrder(Long voucherId){
        //4.判断一人一单 库存如果足够则去扣减库存
        Long userId = UserHolder.getUser().getId();
        Long number = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if(number > 0){
            return Result.fail("每人只能下一单!");
        }
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock",0)//乐观锁 防超卖
                .update();
        if(!success){
            return Result.fail("秒杀券已抢空!");
        }
        //5.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //5.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //5.2用户id
        voucherOrder.setUserId(userId);
        //5.3代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //6.返回订单号
        return Result.ok(orderId);
    }
}
