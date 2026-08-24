package com.witos.ems.server.service;

import java.util.Map;

public interface EmsOpenemsBackfillService
{
    Map<String, Object> create(Long deviceId, Map<String, Object> body);

    Map<String, Object> get(Long id);

    Map<String, Object> list(Map<String, String> query);
}
