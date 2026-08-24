package com.witos.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;


/**
 * 网关启动程序
 *
 * @author witos
 */
@Slf4j
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class })
public class WitOSGatewayApplication
{
    public static void main(String[] args)
    {
        SpringApplication.run(WitOSGatewayApplication.class, args);
        log.info(" (^^)／▽ ▽＼(^^)------witos网关启动成功---(^_^)／★＼(^_^) \n");
    }
}
