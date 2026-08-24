package com.witos.ems.server.service;

import java.util.Map;

public interface EmsWeatherService
{
    Map<String, Object> stationWeather(Long stationId);
}
