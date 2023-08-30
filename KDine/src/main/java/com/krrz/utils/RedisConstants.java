package com.krrz.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "KDine:login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "KDine:login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "KDine:cache:shop:";

    public static final String LOCK_SHOP_KEY = "KDine:lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "KDine:seckill:stock:";
    public static final String BLOG_LIKED_KEY = "KDine:blog:liked:";
    public static final String FEED_KEY = "KDine:feed:";
    public static final String SHOP_GEO_KEY = "KDine:shop:geo:";
    public static final String USER_SIGN_KEY = "KDine:sign:";
}
