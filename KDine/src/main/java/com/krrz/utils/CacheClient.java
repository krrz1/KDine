package com.krrz.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set (String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire (String key, Object value, Long time, TimeUnit unit){
        RedisData redisData=new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    public <T,ID> T queryWithPassThrough(String keyPrefix, ID id, Class<T> type, Function<ID,T> dbFallback,Long time, TimeUnit unit){
        String key=keyPrefix+id;
        //从redis查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //存在 直接返回
            return JSONUtil.toBean(json, type);
        }
        //判断是否为null
        if(json!=null){
            //返回错误信息
            return null;
        }
        //不存在 根据id查询数据库
        T t=dbFallback.apply(id);
        if(t==null){
            //将空值写redis
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            //不存在  返回错误
            return null;
        }
        //存在 写入redis  返回
        this.set(key,t,time,unit);
        return t;
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    public <R,ID> R queryWithLogicExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit unit){
        String key=keyPrefix+id;
        //从redis查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isBlank(json)) {
            //不存在，第一次访问，新增redis逻辑记录
            R apply = dbFallback.apply(id);
            this.setWithLogicalExpire(key,apply,time,unit);
            return apply;
        }
        //命中  需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime=redisData.getExpireTime();
        //判断  是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //未过期 ,直接返回店铺信息
            return r;
        }
        //已过期， 需要缓存重建
        //缓存重建
        //获取互斥锁
        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        //判断是否获取所成功
        if(isLock){
            //再次看一下缓存有没有过期
            String shopJson=stringRedisTemplate.opsForValue().get(key);
            RedisData redisData2 = JSONUtil.toBean(shopJson, RedisData.class);
            JSONObject data2 = (JSONObject) redisData2.getData();
            R r2 = JSONUtil.toBean(data2, type);
            LocalDateTime expireTime2=redisData2.getExpireTime();
            //判断  是否过期
            if(expireTime.isAfter(LocalDateTime.now())){
                //未过期 ,直接返回店铺信息
                return r2;
            }
            //成功，开启独立线程 实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    R apply = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,apply,time,unit);
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
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private boolean unlock(String key){
        Boolean flag = stringRedisTemplate.delete(key);
        return BooleanUtil.isTrue(flag);
    }

}
