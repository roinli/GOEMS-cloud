package com.witos.ems.server.service;

import java.util.Map;

public interface EmsOpenemsCapabilityService
{
    Map<String, Object> refresh(Long edgeId);

    Map<String, Object> graph(Long edgeId);

    Map<String, Object> templates(Long edgeId);

    Map<String, Object> componentCapabilities(Long componentId);
}
