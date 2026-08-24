package com.witos.modules.monitor;

import com.witos.common.security.annotation.EnableCustomConfig;
import com.witos.common.security.annotation.EnableWitOSFeignClients;
import com.witos.common.swagger.annotation.EnableCustomSwagger2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 监控中心
 *
 * @author witos
 */
@Slf4j
@EnableCustomConfig
@EnableCustomSwagger2
@EnableWitOSFeignClients
@SpringBootApplication
@EnableScheduling
public class WitOSMonitorApplication
{
    public static void main(String[] args)
    {
        SpringApplication.run(WitOSMonitorApplication.class, args);
        log.info(" (^^)／▽ ▽＼(^^)------witos监控中心模块启动成功---(^_^)／★＼(^_^) \n");
    }
}
