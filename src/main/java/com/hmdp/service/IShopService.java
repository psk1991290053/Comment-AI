package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 借助缓存查询店铺信息
     * @param id
     * @return
     */
    Result queryById(Long id);

    /**
     * 更新缓存的更新数据库方案
     * @param shop
     */
    Result update(Shop shop);

    /**
     * 根据商铺类型分页查询商铺信息，支持按坐标查询附近商铺
     * @param typeId 商铺类型
     * @param current 页码
     * @param x 经度
     * @param y 纬度
     * @return 商铺列表
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
