package com.witos.ems.server;

import com.witos.common.security.annotation.EnableCustomConfig;
import com.witos.common.security.annotation.EnableWitOSFeignClients;
import com.witos.common.swagger.annotation.EnableCustomSwagger2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * EMS后端模块
 */
@Slf4j
@EnableCustomConfig
@EnableCustomSwagger2
@EnableWitOSFeignClients
@SpringBootApplication
public class EmsServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(EmsServerApplication.class, args);
        log.info(" (^^)／▽ ▽＼(^^)------EMS后端模块启动成功---(^_^)／★＼(^_^)\n");
    }
}
