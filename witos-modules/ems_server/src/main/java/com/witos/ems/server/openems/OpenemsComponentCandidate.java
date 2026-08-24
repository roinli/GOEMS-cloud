package com.witos.ems.server.openems;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class OpenemsComponentCandidate
{
    private Long serverEndpointId;

    private String edgeId;

    private String componentId;

    private String componentType;

    private String componentAlias;

    private String serialNo;

    private String parentEdgeId;

    private String parentComponentId;
    private String model;
    private String manufacturer;
    private String firmwareVersion;
    private String serialNoSource;
    private Boolean online;
    private Object channels;
    private String lastSampleTime;
    private String communicationDeviceId;
    private String communicationDeviceAlias;
    private String communicationDeviceType;
    private String communicationDeviceSerialNo;
    private String communicationDeviceSerialNoSource;
    private String bindToken;

    public Map<String, Object> toMap()
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("serverEndpointId", serverEndpointId);
        row.put("edgeId", edgeId);
        row.put("componentId", componentId);
        row.put("componentType", componentType);
        row.put("componentAlias", componentAlias);
        row.put("serialNo", serialNo);
        row.put("parentEdgeId", parentEdgeId);
        row.put("parentComponentId", parentComponentId);
        row.put("model", model);
        row.put("manufacturer", manufacturer);
        row.put("firmwareVersion", firmwareVersion);
        row.put("serialNoSource", serialNoSource);
        row.put("online", online);
        row.put("channels", channels);
        row.put("lastSampleTime", lastSampleTime);
        row.put("communicationDeviceId", communicationDeviceId);
        row.put("communicationDeviceAlias", communicationDeviceAlias);
        row.put("communicationDeviceType", communicationDeviceType);
        row.put("communicationDeviceSerialNo", communicationDeviceSerialNo);
        row.put("communicationDeviceSerialNoSource", communicationDeviceSerialNoSource);
        row.put("bindToken", bindToken);
        return row;
    }
}