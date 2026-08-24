package com.witos.ems.server.service;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.Date;
import java.util.Map;

public interface EmsOpenemsBindingService
{
    IPage<Map<String, Object>> listDevices(Map<String, String> query);

    Map<String, Object> getDevice(Long id);

    Map<String, Object> bindEdge(Long edgeId, Map<String, Object> body);

    Map<String, Object> bindAllUnboundDevices(Long edgeId, Map<String, Object> body);

    Map<String, Object> bindDevice(Long deviceId, Map<String, Object> body);

    void inheritNewDevice(Long deviceId, Date effectiveFrom, String source);
}
