package com.witos.ems.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "map.qq")
public class EmsQqMapProperties
{
    private String key = "QUWBZ-DBKWC-NCI2J-A557E-LST3Q-77B6X";

    private String geocoderUrl = "https://apis.map.qq.com/ws/geocoder/v1/?key=";

    private String suggestionUrl = "https://apis.map.qq.com/ws/place/v1/suggestion?key=";

    public String getKey()
    {
        return key;
    }

    public void setKey(String key)
    {
        this.key = key;
    }

    public String getGeocoderUrl()
    {
        return geocoderUrl;
    }

    public void setGeocoderUrl(String geocoderUrl)
    {
        this.geocoderUrl = geocoderUrl;
    }

    public String getSuggestionUrl()
    {
        return suggestionUrl;
    }

    public void setSuggestionUrl(String suggestionUrl)
    {
        this.suggestionUrl = suggestionUrl;
    }
}
