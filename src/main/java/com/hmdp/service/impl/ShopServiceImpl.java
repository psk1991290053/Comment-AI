package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 借助缓存查询店铺信息
     * @param id
     * @return
     */
    public Result queryById(Long id) {
        //查询redis
        String shopJsonStr = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //若存在缓存，则直接返回
        if(!StringUtils.isEmpty(shopJsonStr)){
            Shop shop = JSONUtil.toBean(shopJsonStr, Shop.class);
            return Result.ok(shop);
        }
        //防止缓存穿透
        if(shopJsonStr != null){
            //缓存中不存在，又不是null，则为空串
            return Result.fail("店铺不存在!");
        }
        //使用互斥锁来解决缓存击穿问题
        //第一个发现缓存中没有该热点数据的线程--创建锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;

        try {
            if(!BooleanUtil.isTrue(stringRedisTemplate.opsForValue().setIfAbsent(lockKey,"1",10,TimeUnit.SECONDS))){
                //发现缓存中有锁，说明不是第一个线程
                Thread.sleep(20);
                return queryById(id);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        try {
            //若不存在缓存，查询数据库
            shop = getById(id);
            //若不存在，则报错
            if(shop == null){
                //防止缓存穿透
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("店铺不存在!");
            }
            //若存在，则存入缓存
            String shopJson = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, shopJson,CACHE_SHOP_TTL, TimeUnit.MINUTES);//解锁
            //返回店铺信息
            return Result.ok(shop);
        } finally {
            stringRedisTemplate.delete(lockKey);
        }

    }

    /**
     * 更新缓存的更新数据库方案
     * @param shop
     */
    public Result update(Shop shop) {
        if(shop.getId() == null){
            return Result.fail("店铺id不能为空!");
        }
        //先更新数据库
        updateById(shop);
        //再更新缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
