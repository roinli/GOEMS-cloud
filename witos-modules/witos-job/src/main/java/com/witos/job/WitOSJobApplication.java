package com.witos.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.witos.common.security.annotation.EnableCustomConfig;
import com.witos.common.security.annotation.EnableWitOSFeignClients;
import com.witos.common.swagger.annotation.EnableCustomSwagger2;

@Slf4j
@EnableCustomConfig
@EnableCustomSwagger2
@EnableWitOSFeignClients
@SpringBootApplication
public class WitOSJobApplication {

    public static void main(String[] args) {
        SpringApplication.run(WitOSJobApplication.class, args);
        log.info(" (^^)／▽ ▽＼(^^)------witos定时任务模块启动成功---(^_^)／★＼(^_^) \n");
    }

}
