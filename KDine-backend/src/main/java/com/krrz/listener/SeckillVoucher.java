package com.krrz.listener;

import cn.hutool.core.bean.BeanUtil;
import com.krrz.entity.VoucherOrder;
import com.krrz.service.IVoucherOrderService;
import com.krrz.service.impl.VoucherOrderServiceImpl;
import com.krrz.utils.SimpleRedisLock;
import com.rabbitmq.client.Channel;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Component
public class SeckillVoucher {
    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    private Log log = LogFactory.getLog(this.getClass());

    private Jackson2JsonMessageConverter serializer=new Jackson2JsonMessageConverter();

    @RabbitListener(queues = "KDineQueue")
    public void produceOrder(Message message, Channel channel) throws IOException {
        System.out.println("消费者收到的："+message.toString());
        //序列化器转换
        Map value = (Map) serializer.fromMessage(message, Map.class);
        //填满voucher
        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
        //加分布式锁，更新数据库
        boolean success = voucherOrderService.handleVoucherOrder(voucherOrder);
        if(!success){
            channel.basicReject(message.getMessageProperties().getDeliveryTag(),true);
            log.error("消费订单创建失败："+message.toString());
        }else{
            //确认ack
            channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
            log.debug("消费订单成功并确认："+message.toString());
        }
    }
}