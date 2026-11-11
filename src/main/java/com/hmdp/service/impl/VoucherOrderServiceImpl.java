package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_STREAM_KEY;

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

    /** 消费者组 */
    private static final String GROUP_NAME = "g1";
    /** 消费者（每个应用实例一个名字，多实例部署时改成各自的标识，比如 IP+端口） */
    private static final String CONSUMER_NAME = "c1";

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ApplicationContext applicationContext;

    /**
     * 消费线程池：单线程，保证同一个 JVM 内的订单串行落库
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "seckill-order-handler");
        // 设为守护线程，否则应用关闭时 JVM 会因为这个线程一直不退出而卡住
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 事务代理对象。
     * 异步线程里 AopContext 取不到代理（ThreadLocal 是空的），所以在请求线程里取好存下来给它用；
     * 如果消费线程先跑起来（比如重启后 Stream 里还有残留消息），handleVoucherOrder 里会兜底从容器里取。
     */
    private volatile IVoucherOrderService proxy;

    /**
     * 引入Lua脚本
     */
    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 容器启动后：先确保消费者组存在，再拉起消费线程
     */
    @PostConstruct
    private void init() {
        // 组不存在时读会报 NOGROUP，所以必须先建。XGROUP CREATE stream.orders g1 0 MKSTREAM
        try {
            stringRedisTemplate.opsForStream()
                    .createGroup(SECKILL_ORDER_STREAM_KEY, ReadOffset.from("0"), GROUP_NAME);
            log.info("消费者组 {} 创建成功", GROUP_NAME);
        } catch (Exception e) {
            // 组已经存在会抛 BUSYGROUP，这是正常情况，忽略
            log.info("消费者组已存在或创建失败（通常可忽略）: {}", e.getMessage());
        }
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 消费任务：死循环从 Stream 里读订单消息
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    // ">" 表示读最新未投递的消息，读完就进 pending-list，除非 ACK
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME, CONSUMER_NAME),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(SECKILL_ORDER_STREAM_KEY, ReadOffset.lastConsumed())
                    );
                    // 没读到消息就继续下一轮（block 2 秒后才返回空）
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    handleVoucherOrder(toVoucherOrder(record.getValue()));
                    // 处理成功，ACK 确认，消息才会从 pending-list 里移除
                    stringRedisTemplate.opsForStream().acknowledge(SECKILL_ORDER_STREAM_KEY, GROUP_NAME, record.getId());
                } catch (Exception e) {
                    // 出异常说明这条消息没处理完，去 pending-list 里把它捞出来重试
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }
    }

    /**
     * 处理 pending-list 里的异常消息。
     * 消费线程崩了、或者处理到一半失败（没 ACK）的消息都留在这里，需要重新消费。
     */
    private void handlePendingList() {
        while (true) {
            try {
                // XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                // 偏移量用 "0" 表示从 pending-list 里读自己已投递但未确认的消息
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, CONSUMER_NAME),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(SECKILL_ORDER_STREAM_KEY, ReadOffset.from("0"))
                );
                if (list == null || list.isEmpty()) {
                    // pending-list 也空了，说明处理完了，回到主循环
                    break;
                }
                MapRecord<String, Object, Object> record = list.get(0);
                handleVoucherOrder(toVoucherOrder(record.getValue()));
                stringRedisTemplate.opsForStream().acknowledge(SECKILL_ORDER_STREAM_KEY, GROUP_NAME, record.getId());
            } catch (Exception e) {
                log.error("处理pending-list异常", e);
                // 稍微歇一下，避免死循环空转把 CPU 打满
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * 把 Stream 里的一条消息转成订单对象
     * 消息字段就是 Lua 里 xadd 时写的 userId / voucherId / id
     */
    private VoucherOrder toVoucherOrder(Map<Object, Object> values) {
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setUserId(Long.valueOf(values.get("userId").toString()));
        voucherOrder.setVoucherId(Long.valueOf(values.get("voucherId").toString()));
        voucherOrder.setId(Long.valueOf(values.get("id").toString()));
        return voucherOrder;
    }

    /**
     * 处理订单：拿到代理对象调用事务方法。
     * 这里不吞异常，让异常抛到外层，消息才会留在 pending-list 里被重试。
     */
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        IVoucherOrderService service = proxy;
        if (service == null) {
            // 重启后 Stream 里还有残留消息时，请求线程可能一次都没执行过，proxy 还是 null
            service = applicationContext.getBean(IVoucherOrderService.class);
            proxy = service;
        }
        // 必须调用代理对象，直接 this.createVoucherOrder(...) 事务不会生效
        service.createVoucherOrder(voucherOrder);
    }

    /**
     * 根据优惠券id下单秒杀券
     * @param voucherId
     * @return
     */

    public Result orderSeckillVoucherById(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //1.先生成订单号，因为要作为参数传给 Lua，跟着订单消息一起投递到 Stream
        long orderId = redisIdWorker.nextId("order");
        //2.把代理对象准备好，再执行脚本。
        //  脚本执行成功就意味着消息已经进了 Stream，消费线程随时可能取走，所以代理必须先就位
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //3.执行lua脚本：判断资格 + 扣库存 + 记录用户 + 投递消息，四件事原子完成
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        //4.没有下单资格，返回错误信息
        int res = result.intValue();
        if(res != 0){
            return Result.fail(res == 1 ? "库存不足!" : "不允许重复下单!");
        }
        //5.有资格，立刻返回订单号，真正的落库由消费者组异步完成
        return Result.ok(orderId);
    }

        /**public Result orderSeckillVoucherById(Long voucherId) {
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
    }**/

    /**
     * 真正创建订单：扣库存 + 落库。
     * 注意这是异步线程在调，不能再用 UserHolder 取用户（ThreadLocal 是空的），
     * 所以用户id、订单号都从入参 voucherOrder 里拿。
     */
    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        //4.1数据库层再判一次一人一单（Redis是快路径，这里是兜底，防跨实例并发）
        Long number = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if(number > 0){
            log.error("用户重复下单, userId={}, voucherId={}", userId, voucherId);
            return;
        }

        //4.2扣减库存，乐观锁防超卖
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock",0)
                .update();
        if(!success){
            log.error("库存不足, voucherId={}", voucherId);
            return;
        }

        //5.订单落库
        save(voucherOrder);
    }
}
