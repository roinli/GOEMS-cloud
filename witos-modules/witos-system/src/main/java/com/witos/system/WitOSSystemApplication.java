package com.witos.system;

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
public class WitOSSystemApplication
{
    public static void main(String[] args)
    {
        SpringApplication.run(WitOSSystemApplication.class, args);
        log.info(" (^^)／▽ ▽＼(^^)------witos系统模块启动成功---(^_^)／★＼(^_^) \n");
    }
}
