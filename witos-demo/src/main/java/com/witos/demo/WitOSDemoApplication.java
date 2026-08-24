package com.witos.demo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.witos.common.security.annotation.EnableCustomConfig;
import com.witos.common.security.annotation.EnableWitOSFeignClients;
import com.witos.common.swagger.annotation.EnableCustomSwagger2;

/**
 * 系统模块
 *
 * @author witos
 */
@Slf4j
@EnableCustomConfig
@EnableCustomSwagger2
@EnableWitOSFeignClients
@SpringBootApplication
public class WitOSDemoApplication
{
    public static void main(String[] args)
    {
        SpringApplication.run(WitOSDemoApplication.class, args);
        log.info(" (^^)／▽ ▽＼(^^)------witos Demo样例模块启动成功---(^_^)／★＼(^_^) \n");
    }
}
