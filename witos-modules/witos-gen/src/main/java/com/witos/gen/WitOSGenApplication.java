package com.witos.gen;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.witos.common.security.annotation.EnableCustomConfig;
import com.witos.common.security.annotation.EnableWitOSFeignClients;
import com.witos.common.swagger.annotation.EnableCustomSwagger2;

/**
 * 代码生成
 *
 * @author witos
 */
@Slf4j
@EnableCustomConfig
@EnableCustomSwagger2
@EnableWitOSFeignClients
@SpringBootApplication
public class WitOSGenApplication
{
    public static void main(String[] args)
    {
        SpringApplication.run(WitOSGenApplication.class, args);
        log.info(" (^^)／▽ ▽＼(^^)------witos代码生成模块启动成功---(^_^)／★＼(^_^) \n");
    }
}
