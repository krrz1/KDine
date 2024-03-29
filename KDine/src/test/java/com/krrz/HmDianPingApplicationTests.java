package com.krrz;

import com.krrz.entity.Shop;
import com.krrz.service.impl.ShopServiceImpl;
import com.krrz.utils.CacheClient;
import com.krrz.utils.RedisConstants;
import com.krrz.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private ShopServiceImpl shopService;

    private ExecutorService es= Executors.newFixedThreadPool(500);

    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    void testSaveShop() throws InterruptedException {
        Shop shop=shopService.getById(1l);
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY+1l,shop,10l, TimeUnit.SECONDS);
    }

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = ()->{
            for (int i=0;i<100;i++){
                long id = redisIdWorker.nextId("order");
                System.out.println("id:"+id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for(int i=0;i<300;i++){
            es.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time="+(end-begin));
    }

}
