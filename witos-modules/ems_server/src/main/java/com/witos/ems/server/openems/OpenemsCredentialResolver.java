package com.witos.ems.server.openems;

import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class OpenemsCredentialResolver
{
    private final Environment environment;

    public OpenemsCredentialResolver(Environment environment)
    {
        this.environment = environment;
    }

    public String resolve(String credentialRef)
    {
        if (StringUtils.isEmpty(credentialRef))
        {
            return "";
        }
        String value;
        if (credentialRef.startsWith("env:"))
        {
            value = System.getenv(credentialRef.substring(4));
        }
        else if (credentialRef.startsWith("sys:"))
        {
            value = System.getProperty(credentialRef.substring(4));
        }
        else
        {
            value = credentialRef;
        }
        if (StringUtils.isEmpty(value))
        {
            throw new ServiceException("OpenEMS连接凭据未配置：" + credentialRef);
        }
        return value;
    }
}
