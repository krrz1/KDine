package com.krrz.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.krrz.dto.Result;
import com.krrz.entity.VoucherOrder;
import com.krrz.mapper.VoucherOrderMapper;
import com.krrz.service.ISeckillVoucherService;
import com.krrz.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.krrz.utils.RedisIdWorker;
import com.krrz.utils.SimpleRedisLock;
import com.krrz.utils.UserHolder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    RabbitTemplate rabbitTemplate;

    private static final DefaultRedisScript<Long> seckillScript;
    static {
        seckillScript=new DefaultRedisScript<>();
        seckillScript.setLocation(new ClassPathResource("seckill.lua"));
        seckillScript.setResultType(Long.class);
    }

//    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);
    private ExecutorService seckill_order_executor=Executors.newSingleThreadExecutor();

//    @PostConstruct
//    private void init(){
//        seckill_order_executor.submit(new VoucherOrderHandler());
//    }

    private class VoucherOrderHandler implements Runnable{
        String queueName="stream.orders";
        @Override
        public void run() {
            while(true){
                try {
                    //获取消息队列中的订单信息  XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    if(read==null || read.isEmpty()){
                        //如果获取失败，说明没有消息 ，继续下一个循环
                        continue;
                    }
                    MapRecord<String, Object, Object> record = read.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //ack确认  SACK steam.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常!",e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while(true){
                try {
                    //获取pending-list中的订单信息  XREADGROUP GROUP g1 c1 COUNT 1 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //判断消息获取是否成功
                    if(read==null || read.isEmpty()){
                        //如果获取失败，说明没有消息 ，结束循环
                        break;
                    }
                    MapRecord<String, Object, Object> record = read.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //如果获取成功，可以下单
                    handleVoucherOrder(voucherOrder);
                    //ack确认  SACK steam.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常!",e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while(true){
//                try {
//                    //获取队列中的订单信息
//                    VoucherOrder order = orderTasks.take();
//                    //创建订单
//                    handleVoucherOrder(order);
//                } catch (InterruptedException e) {
//                    log.error("处理订单异常!",e);
//                }
//            }
//        }
//    }


    public boolean handleVoucherOrder(VoucherOrder order) {
        Long userId = order.getUserId();
        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("KDine:order:" + userId, stringRedisTemplate);
        boolean islock = simpleRedisLock.tryLock(1200);
        if(!islock){
            //失败 一般不会 因为已经在进入阻塞队列前判断过了
            log.error("不允许重复下单");
        }
        boolean flag=true;
        try {
            //返回订单id
//            proxy.createVoucherOrder(order);
            createVoucherOrder(order);
        } catch (Exception e) {
            e.printStackTrace();
            flag=false;
        }finally {
            simpleRedisLock.unlock();
            return flag;
        }

    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //订单id
        long orderId = redisIdWorker.nextId("order");
        //执行lua脚本
        Long result = stringRedisTemplate.execute(seckillScript,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        //判断结果是否为0
        int r=result.intValue();
        if(r!=0){
            //不为0，代表没有购买资格
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        //为0，有购买资格 。把下单信息保存到 rabbitmq 阻塞队列
        Map<String,Object> map=new HashMap<>();
        map.put("userId",userId);
        map.put("voucherId",voucherId);
        map.put("id",orderId);
        map.put("createTime", new Date().getTime());
        rabbitTemplate.convertAndSend("KDineExchange","myRoutingKey",map);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //返回订单id
        return Result.ok(0);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        //订单id
//        long orderId = redisIdWorker.nextId("order");
//        //执行lua脚本
//        Long result = stringRedisTemplate.execute(seckillScript,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString(),
//                String.valueOf(orderId)
//        );
//        //判断结果是否为0
//        int r=result.intValue();
//        if(r!=0){
//            //不为0，代表没有购买资格
//            return Result.fail(r==1?"库存不足":"不能重复下单");
//        }
//        //为0，有购买资格 。把下单信息保存到阻塞队列
//
//        //获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//        //返回订单id
//        return Result.ok(0);
//    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        //执行lua脚本
//        Long result = stringRedisTemplate.execute(seckillScript, Collections.emptyList(), voucherId.toString(), userId.toString());
//        //判断结果是否为0
//        int r=result.intValue();
//        if(r!=0){
//            //不为0，代表没有购买资格
//            return Result.fail(r==1?"库存不足":"不能重复下单");
//        }
//        //为0，有购买资格 。把下单信息保存到阻塞队列
//        //订单id
//        long orderId = redisIdWorker.nextId("order");
//        VoucherOrder voucherOrder=new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//
//        orderTasks.add(voucherOrder);
//
//        //获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//        //返回订单id
//        return Result.ok(0);
//    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始！");
//        }
//        //判断秒杀是否结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束！");
//        }
//        //判断库存是否充足
//        if(voucher.getStock()<1){
//            return Result.fail("库存不足！");
//        }
//        //一人一单
//        Long userId = UserHolder.getUser().getId();
//        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        boolean islock = simpleRedisLock.tryLock(1200);
//        if(!islock){
//            //失败
//            return Result.fail("一个人只允许下一单");
//        }
//        try {
//            //获取代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            //返回订单id
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            e.printStackTrace();
//        }finally {
//            simpleRedisLock.unlock();
//        }
//        return null;
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单s
        Long userId = voucherOrder.getUserId();

        //查询订单
        int r = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (r > 0) {
            //用户已经购买过了
            log.error("用户已经购买过一次了！");
        }
        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足！");
        }
        //创建订单
        save(voucherOrder);
    }
}
