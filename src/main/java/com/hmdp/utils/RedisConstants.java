package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    public static final String SHOP_TYPE_KEY = "cache:shopType";
    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TYPE_TTL = 30L;
    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";


    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    // 秒杀订单消息队列（Redis Stream），注意 seckill.lua 里也是硬编码这个值，改的时候两边要一起改
    public static final String SECKILL_ORDER_STREAM_KEY = "stream.orders";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
