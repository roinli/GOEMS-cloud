package com.witos.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import com.witos.common.security.annotation.EnableWitOSFeignClients;

/**
 * 认证授权中心
 *
 * @author witos
 */
@Slf4j
@com.witos.common.security.annotation.EnableWitOSFeignClients
@SpringBootApplication(scanBasePackages = { "com.witos.auth", "com.witos.system.api.factory" }, exclude = {DataSourceAutoConfiguration.class })
public class WitOSAuthApplication
{
    public static void main(String[] args)
    {
        SpringApplication.run(WitOSAuthApplication.class, args);
        log.info(" (^^)／▽ ▽＼(^^)------witos权限启动成功---(^_^)／★＼(^_^) \n");
    }
}
