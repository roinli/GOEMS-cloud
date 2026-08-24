package com.witos.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ems.registration")
public class EmsRegistrationProperties
{
    private boolean installerEnabled = false;

    private long emailCodeExpirationMinutes = 10L;

    private long emailRateLimitSeconds = 60L;

    private long ipRateLimitSeconds = 10L;

    private long deviceRateLimitSeconds = 10L;

    public boolean isInstallerEnabled()
    {
        return installerEnabled;
    }

    public void setInstallerEnabled(boolean installerEnabled)
    {
        this.installerEnabled = installerEnabled;
    }

    public long getEmailCodeExpirationMinutes()
    {
        return emailCodeExpirationMinutes;
    }

    public void setEmailCodeExpirationMinutes(long emailCodeExpirationMinutes)
    {
        this.emailCodeExpirationMinutes = emailCodeExpirationMinutes;
    }

    public long getEmailRateLimitSeconds()
    {
        return emailRateLimitSeconds;
    }

    public void setEmailRateLimitSeconds(long emailRateLimitSeconds)
    {
        this.emailRateLimitSeconds = emailRateLimitSeconds;
    }

    public long getIpRateLimitSeconds()
    {
        return ipRateLimitSeconds;
    }

    public void setIpRateLimitSeconds(long ipRateLimitSeconds)
    {
        this.ipRateLimitSeconds = ipRateLimitSeconds;
    }

    public long getDeviceRateLimitSeconds()
    {
        return deviceRateLimitSeconds;
    }

    public void setDeviceRateLimitSeconds(long deviceRateLimitSeconds)
    {
        this.deviceRateLimitSeconds = deviceRateLimitSeconds;
    }

}