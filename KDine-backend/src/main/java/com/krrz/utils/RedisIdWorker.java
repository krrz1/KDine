package com.krrz.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP=1640995200L;
    /*
     *@Description: 序列号的位数
     *@Params:
     *@Return:
     *@Author:krrz
     *@Date:2023/4/24
     */
    private static final long COUNT_BITS=32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix){
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long l = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp=l-BEGIN_TIMESTAMP;
        //生成序列号
        ///获取当前日期 精确到天
        String yyyyMMdd = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long increment = stringRedisTemplate.opsForValue().increment("KDine:"+"icr:" + keyPrefix + ":" + yyyyMMdd);

        //拼接并返回
        return timestamp << COUNT_BITS | increment;
    }
    public static void main(String args[]){
        LocalDateTime time=LocalDateTime.of(2022,1,1,0,0,0);
        long l = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(l);
    }
}
