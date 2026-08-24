package com.witos.ems.server.service;

import java.util.Map;

public interface EmsOpenemsControlService
{
    Map<String, Object> operations(Long deviceId);

    Map<String, Object> control(Long deviceId, Map<String, Object> body);
}
