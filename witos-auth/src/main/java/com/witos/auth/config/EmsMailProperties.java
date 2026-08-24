package com.witos.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "ems.mail")
public class EmsMailProperties implements ApplicationRunner
{
    private final EmsRegistrationProperties registrationProperties;

    private String from;

    private String registrationSubject = "EMS安装商注册邮箱验证码";

    @Value("${spring.mail.host:}")
    private String host;

    @Value("${spring.mail.port:0}")
    private int port;

    @Value("${spring.mail.username:}")
    private String username;

    @Value("${spring.mail.password:}")
    private String password;

    public EmsMailProperties(EmsRegistrationProperties registrationProperties)
    {
        this.registrationProperties = registrationProperties;
    }

    @Override
    public void run(ApplicationArguments args)
    {
        if (registrationProperties.isInstallerEnabled()
                && (!StringUtils.hasText(host) || port <= 0 || !StringUtils.hasText(username)
            || !StringUtils.hasText(password) || !StringUtils.hasText(from)))
        {
            throw new IllegalStateException("安装商自助注册已开启，但平台 SMTP 配置不完整");
        }
    }

    public String getFrom()
    {
        return from;
    }

    public void setFrom(String from)
    {
        this.from = from;
    }

    public String getRegistrationSubject()
    {
        return registrationSubject;
    }

    public void setRegistrationSubject(String registrationSubject)
    {
        this.registrationSubject = registrationSubject;
    }
}