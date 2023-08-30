package com.krrz;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.krrz.mapper")
@SpringBootApplication
public class KDineApplication {

    public static void main(String[] args) {
        SpringApplication.run(KDineApplication.class, args);
    }

}
