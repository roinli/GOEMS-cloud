package com.witos.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import com.witos.common.swagger.annotation.EnableCustomSwagger2;

/**
 * 文件服务
 *
 * @author witos
 */
@Slf4j
@EnableCustomSwagger2
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class })
public class WitOSFileApplication
{
    public static void main(String[] args)
    {
        SpringApplication.run(WitOSFileApplication.class, args);
        log.info(" (^^)／▽ ▽＼(^^)------witos文件服务启动成功---(^_^)／★＼(^_^) \n");
    }
}
