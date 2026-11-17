package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 根据商铺类型分页查询商铺信息，支持按坐标查询附近商铺
     * @param typeId 商铺类型
     * @param current 页码
     * @param x 经度
     * @param y 纬度
     * @return 商铺列表
     */
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}
