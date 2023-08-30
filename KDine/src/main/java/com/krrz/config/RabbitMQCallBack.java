package com.krrz.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

@Slf4j
@Component
public class RabbitMQCallBack implements RabbitTemplate.ConfirmCallback,RabbitTemplate.ReturnCallback {
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /*
     * 向rabbitmqTemplate 注入回调失败的类
     * 后置处理器，其他注解都执行结束才执行
     */
    @PostConstruct
    public void init(){
        rabbitTemplate.setConfirmCallback(this);
        rabbitTemplate.setReturnCallback(this);
    }

    /*
     * 交换机确认回调方法
     */
    @Override
    public void confirm(CorrelationData correlationData, boolean b, String s) {
        if(b){
            log.info("交换机已经收到 ID 为：{} 的消息",correlationData.getId());
        }else{
            log.error("交换机未收到 ID为 {} 的消息,原因是 {}",correlationData.getId(),s);
        }
    }

    /*
     * 交换机到消息队列return方法
     */
    @Override
    public void returnedMessage(Message message, int i, String s, String s1, String s2) {
        log.error("消息 {} ,被交换机 {} 退回原因 {}",message,s1,s2);
    }
}
