package com.witos.ems.server.openems;

import com.witos.common.core.utils.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class OpenemsDeviceDiscoveryService
{
    @Resource
    private OpenemsJsonRpcClient openemsJsonRpcClient;

    public List<OpenemsComponentCandidate> findComponentsBySerialNo(Long serverEndpointId, String serialNo)
    {
        List<OpenemsComponentCandidate> candidates = new ArrayList<OpenemsComponentCandidate>();
        if (StringUtils.isEmpty(serialNo))
        {
            return candidates;
        }
        for (Map<String, Object> row : openemsJsonRpcClient.findComponentsBySerialNo(serverEndpointId, serialNo))
        {
            OpenemsComponentCandidate candidate = fromMap(serverEndpointId, serialNo, row);
            candidate.setBindToken(OpenemsBindTokenCodec.encode(candidate));
            candidates.add(candidate);
        }
        return candidates;
    }

    private OpenemsComponentCandidate fromMap(Long serverEndpointId, String serialNo, Map<String, Object> row)
    {
        OpenemsComponentCandidate candidate = new OpenemsComponentCandidate();
        candidate.setServerEndpointId(asLong(row.get("serverEndpointId"), serverEndpointId));
        candidate.setEdgeId(stringValue(row.get("edgeId")));
        candidate.setComponentId(stringValue(row.get("componentId")));
        candidate.setComponentType(stringValue(row.get("componentType")));
        candidate.setComponentAlias(stringValue(row.get("componentAlias")));
        candidate.setSerialNo(StringUtils.isEmpty(stringValue(row.get("serialNo"))) ? serialNo : stringValue(row.get("serialNo")));
        candidate.setParentEdgeId(stringValue(row.get("parentEdgeId")));
        candidate.setParentComponentId(stringValue(row.get("parentComponentId")));
        candidate.setModel(stringValue(row.get("model")));
        candidate.setManufacturer(stringValue(row.get("manufacturer")));
        candidate.setFirmwareVersion(stringValue(row.get("firmwareVersion")));
        candidate.setSerialNoSource(stringValue(row.get("serialNoSource")));
        candidate.setOnline(asBoolean(row.get("online")));
        candidate.setChannels(row.get("channels"));
        candidate.setLastSampleTime(stringValue(row.get("lastSampleTime")));
        candidate.setCommunicationDeviceId(stringValue(row.get("communicationDeviceId")));
        candidate.setCommunicationDeviceAlias(stringValue(row.get("communicationDeviceAlias")));
        candidate.setCommunicationDeviceType(stringValue(row.get("communicationDeviceType")));
        candidate.setCommunicationDeviceSerialNo(stringValue(row.get("communicationDeviceSerialNo")));
        candidate.setCommunicationDeviceSerialNoSource(stringValue(row.get("communicationDeviceSerialNoSource")));
        return candidate;
    }

    private Long asLong(Object value, Long defaultValue)
    {
        if (value == null || StringUtils.isEmpty(String.valueOf(value)))
        {
            return defaultValue;
        }
        if (value instanceof Number)
        {
            return ((Number) value).longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private String stringValue(Object value)
    {
        return value == null ? "" : String.valueOf(value);
    }

    private Boolean asBoolean(Object value)
    {
        if (value == null || StringUtils.isEmpty(String.valueOf(value)))
        {
            return null;
        }
        if (value instanceof Boolean)
        {
            return (Boolean) value;
        }
        return Boolean.valueOf(String.valueOf(value));
    }
}