package com.witos.ems.server.service;

import java.util.Map;

public interface EmsOpenemsTimeseriesService
{
    Map<String, Object> latest(Long deviceId, Map<String, String> query);

    Map<String, Object> history(Long deviceId, Map<String, String> query);
}
