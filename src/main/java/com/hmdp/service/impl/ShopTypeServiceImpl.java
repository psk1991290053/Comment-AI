package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;
import static com.hmdp.utils.RedisConstants.SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 利用缓存查询店铺种类信息
     * @return
     */
    public List<ShopType> cacheQuery() {
        //先查询redis中有没有缓存
        String shopTypeStr = stringRedisTemplate.opsForValue().get(SHOP_TYPE_KEY);
        //若有则直接返回
        if(shopTypeStr != null){
            List<ShopType> list = JSONUtil.toList(shopTypeStr, ShopType.class);
            return list;
        }
        //若没有则查询数据库
        List<ShopType> list = query().orderByAsc("sort").list();
        //存入缓存
        stringRedisTemplate.opsForValue().set(SHOP_TYPE_KEY, JSONUtil.toJsonStr(list),CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        //返回数据
        return list;
    }
}
