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
}
