package com.witos.ems.server.openems;

import java.util.List;
import java.util.Map;

public interface OpenemsJsonRpcClient
{
    Map<String, Object> createEdge(Long serverEndpointId, String comment);

    List<Map<String, Object>> listEdges(Long serverEndpointId);

    Map<String, Object> getEdgeConfig(Long serverEndpointId, String edgeId);

    Map<String, Object> getAppSnapshot(Long serverEndpointId, String edgeId);

    Map<String, Object> getCapabilitySnapshot(Long serverEndpointId, String edgeId);

    Map<String, Object> createComponentConfig(Long serverEndpointId, String edgeId, String factoryPid,
                                              Map<String, Object> properties);

    Map<String, Object> updateComponentConfig(Long serverEndpointId, String edgeId, String componentId,
                                              Map<String, Object> properties);

    Map<String, Object> addAppInstance(Long serverEndpointId, String edgeId, String appId, String key,
                                       String alias, Map<String, Object> properties);

    Map<String, Object> setChannelValue(Long serverEndpointId, String edgeId, String componentId,
                                        String channelId, Object value);

    Map<String, Object> componentJsonApi(Long serverEndpointId, String edgeId, String componentId,
                                         String method, Map<String, Object> params);

    List<Map<String, Object>> findComponentsBySerialNo(Long serverEndpointId, String serialNo);

    Map<String, Object> getEdgeStatus(Long serverEndpointId, String edgeId);

    Map<String, Object> getRealtimeChannelValues(Long serverEndpointId, String edgeId, List<String> channelAddresses);

    Map<String, Object> queryHistoricTimeseries(Long serverEndpointId, String edgeId, List<String> channelAddresses,
                                                String fromTime, String toTime);
}
