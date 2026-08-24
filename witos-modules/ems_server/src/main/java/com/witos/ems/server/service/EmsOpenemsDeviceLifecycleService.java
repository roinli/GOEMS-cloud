package com.witos.ems.server.service;

import java.util.Map;

public interface EmsOpenemsDeviceLifecycleService
{
    Map<String, Object> disable(Long deviceId);

    Map<String, Object> enable(Long deviceId);

    Map<String, Object> removeFailed(Long deviceId);

    Map<String, Object> resourceReport(Long deviceId, Map<String, String> query);
}
