package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 根据优惠券id下单秒杀券
     * @param voucherId
     * @return
     */
    Result orderSeckillVoucherById(Long voucherId);

    /**
     * 真正创建订单（扣库存 + 落库），由阻塞队列的消费线程通过代理调用
     * @param voucherOrder 已经生成好订单号、用户id、券id的订单对象
     */
    void createVoucherOrder(VoucherOrder voucherOrder);
}
