package com.witos.ems.server.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.common.core.utils.http.HttpUtils;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.config.EmsWeatherProperties;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.ems.server.service.EmsWeatherService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EmsWeatherServiceImpl implements EmsWeatherService
{
    @Resource
    private EmsStationMapper stationMapper;

    @Resource
    private EmsAuthScopeService authScopeService;

    @Resource
    private EmsWeatherProperties weatherProperties;

    private final Map<String, CachedWeather> cache = new ConcurrentHashMap<String, CachedWeather>();

    @Override
    public Map<String, Object> stationWeather(Long stationId)
    {
        if (stationId == null)
        {
            throw new ServiceException("电站不能为空");
        }
        Map<String, Object> station = stationMapper.selectStationDetail(stationId, authScopeService.currentScope());
        if (station == null || station.isEmpty())
        {
            throw new ServiceException("电站不存在或无权访问");
        }
        BigDecimal longitude = decimal(station.get("longitude"));
        BigDecimal latitude = decimal(station.get("latitude"));
        if (longitude == null || latitude == null)
        {
            throw new ServiceException("电站未配置经纬度，无法查询天气");
        }

        String cacheKey = stationId + ":" + latitude.stripTrailingZeros().toPlainString()
                + "," + longitude.stripTrailingZeros().toPlainString();
        CachedWeather cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt > System.currentTimeMillis())
        {
            return new LinkedHashMap<String, Object>(cached.data);
        }

        Map<String, Object> weather = fetchWeather(stationId, latitude, longitude);
        long cacheMillis = Math.max(1, weatherProperties.getCacheMinutes()) * 60L * 1000L;
        cache.put(cacheKey, new CachedWeather(weather, System.currentTimeMillis() + cacheMillis));
        return weather;
    }

    private Map<String, Object> fetchWeather(Long stationId, BigDecimal latitude, BigDecimal longitude)
    {
        String url = weatherProperties.getForecastUrl()
                + "?latitude=" + latitude.toPlainString()
                + "&longitude=" + longitude.toPlainString()
                + "&current=temperature_2m,weather_code,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,wind_speed_10m_max"
                + "&timezone=auto&forecast_days=5";
        String response = HttpUtils.sendGet(url);
        if (StringUtils.isEmpty(response))
        {
            throw new ServiceException("天气服务暂不可用");
        }
        JSONObject root = JSON.parseObject(response);
        if (root == null || root.containsKey("error"))
        {
            throw new ServiceException("天气服务返回异常");
        }

        JSONObject currentSource = root.getJSONObject("current");
        JSONObject dailySource = root.getJSONObject("daily");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("stationId", stationId);
        result.put("latitude", latitude);
        result.put("longitude", longitude);
        result.put("timezone", root.getString("timezone"));

        Map<String, Object> current = new LinkedHashMap<String, Object>();
        if (currentSource != null)
        {
            current.put("time", currentSource.getString("time"));
            current.put("temperature", currentSource.get("temperature_2m"));
            current.put("weatherCode", currentSource.get("weather_code"));
            current.put("windSpeed", currentSource.get("wind_speed_10m"));
        }
        result.put("current", current);
        result.put("daily", buildDailyRows(dailySource));
        return result;
    }

    private List<Map<String, Object>> buildDailyRows(JSONObject daily)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (daily == null)
        {
            return rows;
        }
        JSONArray times = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray maximums = daily.getJSONArray("temperature_2m_max");
        JSONArray minimums = daily.getJSONArray("temperature_2m_min");
        JSONArray winds = daily.getJSONArray("wind_speed_10m_max");
        int size = times == null ? 0 : times.size();
        for (int index = 0; index < size; index++)
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("date", valueAt(times, index));
            row.put("weatherCode", valueAt(codes, index));
            row.put("maxTemperature", valueAt(maximums, index));
            row.put("minTemperature", valueAt(minimums, index));
            row.put("maxWindSpeed", valueAt(winds, index));
            rows.add(row);
        }
        return rows;
    }

    private Object valueAt(JSONArray values, int index)
    {
        return values != null && index < values.size() ? values.get(index) : null;
    }

    private BigDecimal decimal(Object value)
    {
        if (value == null || String.valueOf(value).trim().isEmpty())
        {
            return null;
        }
        try
        {
            return new BigDecimal(String.valueOf(value));
        }
        catch (NumberFormatException ex)
        {
            return null;
        }
    }

    private static class CachedWeather
    {
        private final Map<String, Object> data;
        private final long expiresAt;

        private CachedWeather(Map<String, Object> data, long expiresAt)
        {
            this.data = new LinkedHashMap<String, Object>(data);
            this.expiresAt = expiresAt;
        }
    }
}
