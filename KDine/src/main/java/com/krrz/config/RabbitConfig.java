package com.krrz.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    public static final String QUEUE_NAME = "KDineQueue";
    public static final String EXCHANGE_NAME="KDineExchange";

    @Bean
    public Queue myQueue(){
        return new Queue(QUEUE_NAME,true,false,false);
    }
    @Bean
    public DirectExchange myExchange(){
        return new DirectExchange(EXCHANGE_NAME);
    }
    @Bean
    public Binding binding(){
        return BindingBuilder.bind(myQueue()).to(myExchange()).with("myRoutingKey");
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());
        return rabbitTemplate;
    }
}
