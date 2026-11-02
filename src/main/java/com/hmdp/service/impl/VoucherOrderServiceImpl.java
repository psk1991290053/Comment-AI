package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

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

    /**
     * 根据优惠券id下单秒杀券
     * @param voucherId
     * @return
     */
    public Result orderSeckillVoucherById(Long voucherId) {
        //1.查询秒杀卷时间是否合法
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀还未开始!");
        }
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束!");
        }
        //2.查询秒杀券库存是否足够
        if(!(seckillVoucher.getStock() >= 1)) {
            return Result.fail("秒杀券已抢空!");
        }
        //3.库存如果足够则去扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).update();
        if(!success){
            return Result.fail("秒杀券已抢空!");
        }
        //4.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //4.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //4.2用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        //4.3代金券id
        voucherOrder.setVoucherId(voucherId);
        //5.返回订单号
        return Result.ok(orderId);
    }
}
