package com.witos.ems.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "weather.open-meteo")
public class EmsWeatherProperties
{
    private String forecastUrl = "https://api.open-meteo.com/v1/forecast";

    private int cacheMinutes = 10;

    public String getForecastUrl()
    {
        return forecastUrl;
    }

    public void setForecastUrl(String forecastUrl)
    {
        this.forecastUrl = forecastUrl;
    }

    public int getCacheMinutes()
    {
        return cacheMinutes;
    }

    public void setCacheMinutes(int cacheMinutes)
    {
        this.cacheMinutes = cacheMinutes;
    }
}
