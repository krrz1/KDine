package com.krrz.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.krrz.dto.Result;
import com.krrz.entity.Shop;
import com.krrz.mapper.ShopMapper;
import com.krrz.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.krrz.utils.CacheClient;
import com.krrz.utils.RedisConstants;
import com.krrz.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
//        解决缓存穿透
//          Shop shop = cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,id2->getById(id2),RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
//        互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicExpire(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,20l,TimeUnit.SECONDS);
        if(shop==null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    public Shop queryWithLogicExpire(Long id){
        //从redis查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        if (StrUtil.isBlank(json)) {
            //存在 直接返回
            return null;
        }
        //命中  需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime=redisData.getExpireTime();
        //判断  是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期 ,直接返回店铺信息
            return shop;
        }
        //已过期， 需要缓存重建
        //缓存重建
        //获取互斥锁
        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        //判断是否获取所成功
        if(isLock){
            //再次看一下缓存有没有过期
            String shopJson=stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
            RedisData redisData2 = JSONUtil.toBean(shopJson, RedisData.class);
            JSONObject data2 = (JSONObject) redisData2.getData();
            Shop shop2 = JSONUtil.toBean(data2, Shop.class);
            LocalDateTime expireTime2=redisData2.getExpireTime();
            //判断  是否过期
            if(expireTime.isAfter(LocalDateTime.now())){
                //未过期 ,直接返回店铺信息
                return shop;
            }
            //成功，开启独立线程 实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    this.saveShop2Redis(id,RedisConstants.CACHE_SHOP_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放所
                    unlock(lockKey);
                }

            });
        }

        //返回过期商品信息
        //不存在 根据id查询数据库
        Shop shopById = getById(id);

        //存在 写入redis  返回
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    public Shop queryWithMutex(Long id){
        //从redis查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //存在 直接返回
            Shop shop = JSONUtil.toBean(json, Shop.class);
            return shop;
        }
        //判断是否为null
        if(json!=null){
            //返回错误信息
            return null;
        }
        String lockKey= null;
        Shop shop = null;

        try {
            //实现缓存重建
            //获取互斥锁
            lockKey = RedisConstants.LOCK_SHOP_KEY+id;
            boolean b = tryLock(lockKey);
            if(!b){
                //判断是否成功
                //失败则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //成功 根据id查询数据库
            shop = getById(id);
            Thread.sleep(200);
            if(shop==null){
                //将空值写redis
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                //不存在  返回错误
                return null;
            }
            //存在 写入redis  返回
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }finally {
            //释放互斥锁
            unlock(lockKey);
        }

        return shop;
    }

    public Shop queryWithPassThrough(Long id){
        //从redis查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //存在 直接返回
            Shop shop = JSONUtil.toBean(json, Shop.class);
            return shop;
        }
        //判断是否为null
        if(json!=null){
            //返回错误信息
            return null;
        }
        //不存在 根据id查询数据库
        Shop shop = getById(id);
        if(shop==null){
            //将空值写redis
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            //不存在  返回错误
            return null;
        }
        //存在 写入redis  返回
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private boolean unlock(String key){
        Boolean flag = stringRedisTemplate.delete(key);
        return BooleanUtil.isTrue(flag);
    }

    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1. 查询店铺数据
        Shop shop=getById(id);
        Thread.sleep(200);
        //2. 封装逻辑过期
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入reids
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id=shop.getId();
        if(id==null){
            return Result.fail("店铺id不为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }
}
