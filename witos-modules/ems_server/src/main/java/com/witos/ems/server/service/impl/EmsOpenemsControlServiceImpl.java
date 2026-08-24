package com.witos.ems.server.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.domain.entity.EmsOpenemsCapability;
import com.witos.ems.server.domain.entity.EmsOpenemsCommand;
import com.witos.ems.server.domain.entity.EmsOpenemsDevice;
import com.witos.ems.server.domain.entity.EmsOpenemsEdge;
import com.witos.ems.server.domain.entity.EmsOpenemsComponent;
import com.witos.ems.server.mapper.EmsOpenemsCapabilityMapper;
import com.witos.ems.server.mapper.EmsOpenemsCommandMapper;
import com.witos.ems.server.mapper.EmsOpenemsDeviceMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeMapper;
import com.witos.ems.server.mapper.EmsOpenemsComponentMapper;
import com.witos.ems.server.openems.OpenemsJsonRpcClient;
import com.witos.ems.server.service.EmsOpenemsControlService;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EmsOpenemsControlServiceImpl implements EmsOpenemsControlService
{
    @Resource private EmsOpenemsDeviceMapper deviceMapper;
    @Resource private EmsOpenemsEdgeMapper edgeMapper;
    @Resource private EmsOpenemsComponentMapper componentMapper;
    @Resource private EmsOpenemsCapabilityMapper capabilityMapper;
    @Resource private EmsOpenemsCommandMapper commandMapper;
    @Resource private OpenemsJsonRpcClient jsonRpcClient;

    @Override
    public Map<String, Object> operations(Long deviceId)
    {
        EmsOpenemsDevice device = requireDevice(deviceId);
        Long tenantId = device.getTenantId();
        EmsOpenemsEdge edge = requireEdge(device, tenantId);
        EmsOpenemsComponent component = componentMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsComponent>()
                .eq(EmsOpenemsComponent::getTenantId, tenantId).eq(EmsOpenemsComponent::getEndpointId, device.getEndpointId())
                .eq(EmsOpenemsComponent::getEdgeId, device.getEdgeId())
                .eq(EmsOpenemsComponent::getComponentId, device.getPrimaryComponentId())
                .eq(EmsOpenemsComponent::getDelFlag, "0").last("limit 1"));
        boolean simulatorMode = component != null && StringUtils.isNotEmpty(component.getFactoryPid())
                && component.getFactoryPid().startsWith("Simulator.");
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        List<EmsOpenemsCapability> capabilities = capabilityMapper.selectList(new LambdaQueryWrapper<EmsOpenemsCapability>()
                .eq(EmsOpenemsCapability::getTenantId, tenantId).eq(EmsOpenemsCapability::getEndpointId, device.getEndpointId())
                .eq(EmsOpenemsCapability::getEdgeId, device.getEdgeId())
                .eq(EmsOpenemsCapability::getComponentId, device.getPrimaryComponentId())
                .eq(EmsOpenemsCapability::getStatus, "ACTIVE").eq(EmsOpenemsCapability::getDelFlag, "0"));
        for (EmsOpenemsCapability capability : capabilities)
        {
            if (capability.getCapabilityKey() == null || !capability.getCapabilityKey().startsWith("channel:")) continue;
            JSONObject schema = StringUtils.isEmpty(capability.getChannelSchema()) ? new JSONObject()
                    : JSON.parseObject(capability.getChannelSchema());
            if (!"RW".equals(schema.getString("accessMode")) && !"WO".equals(schema.getString("accessMode"))) continue;
            Map<String, Object> operation = new LinkedHashMap<String, Object>();
            operation.put("operation", "SET_CHANNEL_VALUE"); operation.put("operationSource", "CHANNEL");
            operation.put("controlKind", "CHANNEL");
            operation.put("componentId", device.getPrimaryComponentId()); operation.put("channelId", schema.getString("id"));
            operation.put("valueType", schema.getString("type")); operation.put("unit", schema.getString("unit"));
            operation.put("sourceCapability", capability.getCapabilityKey()); records.add(operation);
        }
        String nature = component == null ? "" : EmsRequestSupport.defaultString(component.getNatureJson(), "");
        addFixedPowerOperation(records, edge, nature, "ManagedSymmetricEss", "Controller.Ess.FixActivePower", "ESS_FIXED_POWER");
        addFixedPowerOperation(records, edge, nature, "PvInverter", "Controller.PvInverter.FixPowerLimit", "PV_POWER_LIMIT");
        addFixedPowerOperation(records, edge, nature, "Evcs", "Controller.Evcs.FixActivePower", "EVCS_FIXED_POWER");
        addSimulatorDatasourceOperation(records, edge, component);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("deviceId", deviceId); result.put("edgeOnline", "ONLINE".equals(edge.getOnlineStatus()));
        result.put("deviceStatus", device.getStatus()); result.put("controlEnabled", "ONLINE".equals(edge.getOnlineStatus()) && "ACTIVE".equals(device.getStatus()));
        result.put("deviceSourceType", device.getSourceType());
        result.put("deviceMode", simulatorMode ? "SIMULATOR" : "FIELD");
        result.put("simulatorMode", simulatorMode);
        result.put("records", records);
        result.put("unsupportedReason", records.isEmpty() ? "目标设备没有发现可写Channel或已安装的官方固定功率Controller" : null);
        return result;
    }

    @Override
    public Map<String, Object> control(Long deviceId, Map<String, Object> body)
    {
        EmsOpenemsDevice device = requireDevice(deviceId);
        Long tenantId = device.getTenantId();
        EmsOpenemsEdge edge = requireEdge(device, tenantId);

        String operation = required(body, "operation", "控制操作不能为空");
        String source = EmsRequestSupport.defaultString(body.get("operationSource"), "CHANNEL").toUpperCase();
        String componentId = EmsRequestSupport.defaultString(body.get("componentId"), device.getPrimaryComponentId());
        String channelId = EmsRequestSupport.defaultString(body.get("channelId"), "");
        Date now = new Date();
        EmsOpenemsCommand command = existingCommand(body, tenantId);
        if (command != null) return response(command);
        command = new EmsOpenemsCommand();
        command.setTenantId(tenantId);
        command.setRequestId(EmsRequestSupport.defaultString(body.get("requestId"), UUID.randomUUID().toString()));
        command.setEndpointId(device.getEndpointId());
        command.setEdgeId(device.getEdgeId());
        command.setComponentId(componentId);
        command.setDeviceId(deviceId);
        command.setOperation(operation);
        command.setOperationSource(source);
        command.setPayloadJson(JSON.toJSONString(body));
        command.setPayloadHash(sha256(command.getPayloadJson()));
        command.setStatus("SENT");
        command.setSentAt(now);
        command.setDelFlag("0");
        command.setCreateTime(now);
        command.setUpdateTime(now);
        commandMapper.insert(command);
        try
        {
            if (!"ACTIVE".equals(device.getStatus())) throw new ServiceException("设备当前状态不允许控制：" + device.getStatus());
            if (!"ONLINE".equals(edge.getOnlineStatus())) throw new ServiceException("Edge离线，不能执行控制");
        Map<String, Object> result;
        if ("CHANNEL".equals(source))
        {
            validateWritableChannel(edge, componentId, channelId);
            if (body.get("value") == null) throw new ServiceException("Channel写入值不能为空");
            result = wrapControlResult(device, operation, source, "CHANNEL", componentId, channelId,
                    jsonRpcClient.setChannelValue(device.getEndpointId(), device.getEdgeId(), componentId,
                            channelId, body.get("value")));
        }
        else if ("ROUTE".equals(source))
        {
            validateRoute(edge, operation);
            result = wrapControlResult(device, operation, source, "ROUTE", componentId, channelId,
                    jsonRpcClient.componentJsonApi(device.getEndpointId(), device.getEdgeId(), componentId,
                            operation, object(body.get("params"))));
        }
        else if ("CONFIG".equals(source))
        {
            result = "SIMULATOR_DATASOURCE".equals(operation)
                        ? simulatorDatasourcePower(device, edge, componentId, body)
                        : fixedPower(device, edge, operation, body);
            result = wrapControlResult(device, operation, source, "CONFIG", componentId, channelId, result);
        }
        else throw new ServiceException("不支持的能力来源：" + source);
        command.setStatus("SUCCESS");
        command.setResponseJson(JSON.toJSONString(result));
            command.setResponseAt(new Date());
            command.setUpdateTime(new Date());
            commandMapper.updateById(command);
            return response(command);
        }
        catch (Exception ex)
        {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            command.setStatus(message.toLowerCase().contains("timeout") || message.contains("超时")
                    ? "TIMEOUT_UNKNOWN" : "FAILED");
            command.setErrorCode(command.getStatus());
            command.setErrorMessage(message);
            command.setResponseAt(new Date());
            command.setUpdateTime(new Date());
            commandMapper.updateById(command);
            if (ex instanceof ServiceException) throw (ServiceException) ex;
            throw new ServiceException("OpenEMS控制调用失败：" + message);
        }
    }

    private Map<String, Object> fixedPower(EmsOpenemsDevice device, EmsOpenemsEdge edge, String operation,
                                           Map<String, Object> body)
    {
        String factory;
        String targetProperty;
        String powerProperty;
        if ("ESS_FIXED_POWER".equals(operation) || "ESS_FIXED_POWER_DISABLE".equals(operation))
        {
            factory = "Controller.Ess.FixActivePower"; targetProperty = "ess.id"; powerProperty = "power";
        }
        else if ("PV_POWER_LIMIT".equals(operation))
        {
            factory = "Controller.PvInverter.FixPowerLimit"; targetProperty = "pvInverter.id"; powerProperty = "powerLimit";
        }
        else if ("EVCS_FIXED_POWER".equals(operation))
        {
            factory = "Controller.Evcs.FixActivePower"; targetProperty = "evcs.id"; powerProperty = "power";
        }
        else throw new ServiceException("不支持的配置控制操作：" + operation);
        validateFactory(edge, factory);
        if (!operation.endsWith("_DISABLE") && body.get("value") == null) throw new ServiceException("固定功率值不能为空");
        JSONObject config = JSON.parseObject(JSON.toJSONString(jsonRpcClient.getEdgeConfig(device.getEndpointId(), device.getEdgeId())));
        JSONObject components = config.getJSONObject("components");
        if (components == null) components = new JSONObject();
        String controllerId = findController(components, factory, targetProperty, device.getPrimaryComponentId());
        boolean create = StringUtils.isEmpty(controllerId);
        JSONObject fixedScheduler = null;
        if (create)
        {
            fixedScheduler = fixedOrderScheduler(components);
            controllerId = "ctrlEms" + device.getId();
            if (components.containsKey(controllerId)) throw new ServiceException("OpenEMS 中已存在控制器Component：" + controllerId);
        }
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put(powerProperty, operation.endsWith("_DISABLE") ? 0 : body.get("value"));
        if (factory.equals("Controller.Ess.FixActivePower")) properties.put("mode", operation.endsWith("_DISABLE") ? "MANUAL_OFF" : "MANUAL_ON");
        if (create)
        {
            properties.put("id", controllerId); properties.put("alias", "EMS " + device.getDisplayName());
            properties.put("enabled", true); properties.put(targetProperty, device.getPrimaryComponentId());
            jsonRpcClient.createComponentConfig(device.getEndpointId(), device.getEdgeId(), factory, properties);
            if (fixedScheduler != null)
            {
                String schedulerId = fixedScheduler.getString("id");
                List<Object> ids = new ArrayList<Object>();
                Object existing = fixedScheduler.get("controllers.ids");
                if (existing instanceof List) ids.addAll((List<?>) existing);
                if (!ids.contains(controllerId)) ids.add(controllerId);
                jsonRpcClient.updateComponentConfig(device.getEndpointId(), device.getEdgeId(), schedulerId,
                        java.util.Collections.<String, Object>singletonMap("controllers.ids", ids));
            }
        }
        else jsonRpcClient.updateComponentConfig(device.getEndpointId(), device.getEdgeId(), controllerId, properties);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("controllerId", controllerId); result.put("factoryPid", factory);
        result.put("action", create ? "CREATED" : "UPDATED"); result.put("rpcAccepted", true);
        result.put("fieldResultNotVerified", true);
        return result;
    }

    private String findController(JSONObject components, String factory, String targetProperty, String targetId)
    {
        for (Map.Entry<String, Object> entry : components.entrySet())
        {
            JSONObject component = JSON.parseObject(JSON.toJSONString(entry.getValue()));
            JSONObject properties = component.getJSONObject("properties");
            if (factory.equals(component.getString("factoryId")) && properties != null
                    && targetId.equals(properties.getString(targetProperty))) return entry.getKey();
        }
        return "";
    }

    private JSONObject fixedOrderScheduler(JSONObject components)
    {
        JSONObject fixed = null;
        for (Map.Entry<String, Object> entry : components.entrySet())
        {
            JSONObject component = JSON.parseObject(JSON.toJSONString(entry.getValue()));
            if ("Scheduler.AllAlphabetically".equals(component.getString("factoryId"))) return null;
            if ("Scheduler.FixedOrder".equals(component.getString("factoryId")))
            {
                fixed = component.getJSONObject("properties");
                if (fixed == null) fixed = new JSONObject();
                fixed.put("id", entry.getKey());
            }
        }
        if (fixed == null) throw new ServiceException("目标Edge没有可确认的AllAlphabetically或FixedOrder Scheduler，不能安全创建控制器");
        return fixed;
    }

    private void validateFactory(EmsOpenemsEdge edge, String factory)
    {
        EmsOpenemsCapability capability = findActiveCapability(edge, "factory:" + factory);
        if (capability == null || !"ACTIVE".equals(capability.getStatus()))
            throw new ServiceException("目标设备当前不支持该控制功能，请重新打开控制面板");
    }

    private void addFixedPowerOperation(List<Map<String, Object>> records, EmsOpenemsEdge edge, String nature,
                                        String natureMarker, String factory, String operationName)
    {
        if (!nature.contains(natureMarker)) return;
        Long count = capabilityMapper.selectCount(new LambdaQueryWrapper<EmsOpenemsCapability>()
                .eq(EmsOpenemsCapability::getTenantId, edge.getTenantId()).eq(EmsOpenemsCapability::getEndpointId, edge.getEndpointId())
                .eq(EmsOpenemsCapability::getEdgeId, edge.getEdgeId()).eq(EmsOpenemsCapability::getCapabilityKey, "factory:" + factory)
                .eq(EmsOpenemsCapability::getStatus, "ACTIVE").eq(EmsOpenemsCapability::getDelFlag, "0"));
        if (count == null || count == 0) return;
        Map<String, Object> operation = new LinkedHashMap<String, Object>();
        operation.put("operation", operationName); operation.put("operationSource", "CONFIG");
        operation.put("controlKind", "FIELD");
        operation.put("factoryPid", factory); operation.put("sourceCapability", "factory:" + factory);
        operation.put("rpcResultOnly", true); records.add(operation);
    }

    private void addSimulatorDatasourceOperation(List<Map<String, Object>> records, EmsOpenemsEdge edge,
                                                 EmsOpenemsComponent component)
    {
        if (component == null || StringUtils.isEmpty(component.getFactoryPid())
                || !component.getFactoryPid().startsWith("Simulator."))
        {
            return;
        }
        JSONObject raw = StringUtils.isEmpty(component.getRawJson()) ? new JSONObject()
                : JSON.parseObject(component.getRawJson());
        JSONObject properties = raw.getJSONObject("properties");
        if (properties == null || StringUtils.isEmpty(properties.getString("datasource.id")))
        {
            return;
        }
        Long count = capabilityMapper.selectCount(new LambdaQueryWrapper<EmsOpenemsCapability>()
                .eq(EmsOpenemsCapability::getTenantId, edge.getTenantId())
                .eq(EmsOpenemsCapability::getEndpointId, edge.getEndpointId())
                .eq(EmsOpenemsCapability::getEdgeId, edge.getEdgeId())
                .eq(EmsOpenemsCapability::getCapabilityKey, "factory:Simulator.Datasource.Single.Channel")
                .eq(EmsOpenemsCapability::getStatus, "ACTIVE")
                .eq(EmsOpenemsCapability::getDelFlag, "0"));
        if (count == null || count == 0)
        {
            return;
        }
        Map<String, Object> operation = new LinkedHashMap<String, Object>();
        operation.put("operation", "SIMULATOR_DATASOURCE");
        operation.put("operationSource", "CONFIG");
        operation.put("controlKind", "SIMULATOR");
        operation.put("channelId", "Data");
        operation.put("valueType", "INTEGER");
        operation.put("unit", "W");
        operation.put("sourceCapability", "factory:Simulator.Datasource.Single.Channel");
        operation.put("description", "模拟器输入功率（自动切换为独立可写Datasource）");
        records.add(operation);
    }

    private Map<String, Object> simulatorDatasourcePower(EmsOpenemsDevice device, EmsOpenemsEdge edge,
                                                          String componentId, Map<String, Object> body)
    {
        if (body.get("value") == null)
        {
            throw new ServiceException("模拟器功率值不能为空");
        }
        EmsOpenemsComponent component = componentMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsComponent>()
                .eq(EmsOpenemsComponent::getTenantId, device.getTenantId())
                .eq(EmsOpenemsComponent::getEndpointId, device.getEndpointId())
                .eq(EmsOpenemsComponent::getEdgeId, device.getEdgeId())
                .eq(EmsOpenemsComponent::getComponentId, componentId)
                .eq(EmsOpenemsComponent::getDelFlag, "0").last("limit 1"));
        if (component == null)
        {
            throw new ServiceException("模拟器组件不存在：" + componentId);
        }
        JSONObject raw = StringUtils.isEmpty(component.getRawJson()) ? new JSONObject()
                : JSON.parseObject(component.getRawJson());
        JSONObject properties = raw.getJSONObject("properties");
        String datasourceId = properties == null ? null : properties.getString("datasource.id");
        if (StringUtils.isEmpty(datasourceId))
        {
            throw new ServiceException("模拟器组件未配置Datasource引用：" + componentId);
        }
        JSONObject config = JSON.parseObject(JSON.toJSONString(jsonRpcClient.getEdgeConfig(device.getEndpointId(), device.getEdgeId())));
        JSONObject components = config.getJSONObject("components");
        if (components == null) components = new JSONObject();
        JSONObject datasource = components.getJSONObject(datasourceId);
        if (datasource == null || !"Simulator.Datasource.Single.Channel".equals(datasource.getString("factoryId")))
        {
            validateFactory(edge, "Simulator.Datasource.Single.Channel");
            String replacementId = "emsSimData" + device.getId();
            if (components.containsKey(replacementId) && !replacementId.equals(datasourceId))
            {
                throw new ServiceException("OpenEMS中已存在模拟Datasource组件：" + replacementId);
            }
            Map<String, Object> datasourceProperties = new LinkedHashMap<String, Object>();
            datasourceProperties.put("id", replacementId);
            datasourceProperties.put("alias", "EMS " + device.getDisplayName() + " 功率输入");
            datasourceProperties.put("enabled", true);
            datasourceProperties.put("timeDelta", -1);
            jsonRpcClient.createComponentConfig(device.getEndpointId(), device.getEdgeId(),
                    "Simulator.Datasource.Single.Channel", datasourceProperties);
            datasourceId = replacementId;
            jsonRpcClient.updateComponentConfig(device.getEndpointId(), device.getEdgeId(), componentId,
                    java.util.Collections.<String, Object>singletonMap("datasource.id", datasourceId));
        }
        Map<String, Object> result = jsonRpcClient.setChannelValue(device.getEndpointId(), device.getEdgeId(),
                datasourceId, "Data", body.get("value"));
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("datasourceId", datasourceId);
        output.put("channelId", "Data");
        output.put("value", body.get("value"));
        output.put("rpcResult", result);
        output.put("fieldResultNotVerified", true);
        return output;
    }

    private EmsOpenemsDevice requireDevice(Long deviceId)
    {
        EmsOpenemsDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsDevice>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsDevice::getTenantId,
                        EmsRequestSupport.currentTenantId())
                .eq(EmsOpenemsDevice::getId, deviceId)
                .eq(EmsOpenemsDevice::getDelFlag, "0").last("limit 1"));
        if (device == null) throw new ServiceException("OpenEMS设备不存在或不属于当前租户");
        return device;
    }

    private EmsOpenemsEdge requireEdge(EmsOpenemsDevice device, Long tenantId)
    {
        EmsOpenemsEdge edge = edgeMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsEdge>()
                .eq(EmsOpenemsEdge::getTenantId, tenantId).eq(EmsOpenemsEdge::getEndpointId, device.getEndpointId())
                .eq(EmsOpenemsEdge::getEdgeId, device.getEdgeId()).eq(EmsOpenemsEdge::getDelFlag, "0").last("limit 1"));
        if (edge == null) throw new ServiceException("设备关联的Edge不存在");
        return edge;
    }

    private void validateWritableChannel(EmsOpenemsEdge edge, String componentId, String channelId)
    {
        if (StringUtils.isEmpty(componentId) || StringUtils.isEmpty(channelId)) throw new ServiceException("必须指定Component和Channel");
        EmsOpenemsCapability capability = findActiveCapability(edge, "channel:" + componentId + "/" + channelId);
        if (capability == null || !"ACTIVE".equals(capability.getStatus()))
            throw new ServiceException("该控制项当前不可用，请重新打开控制面板");
        JSONObject schema = StringUtils.isEmpty(capability.getChannelSchema()) ? new JSONObject() : JSON.parseObject(capability.getChannelSchema());
        String accessMode = schema.getString("accessMode");
        if (!"RW".equals(accessMode) && !"WO".equals(accessMode)) throw new ServiceException("该控制项只支持查看，不能修改");
    }

    private void validateRoute(EmsOpenemsEdge edge, String method)
    {
        EmsOpenemsCapability capability = findActiveCapability(edge, "route:" + method);
        if (capability == null || !"ACTIVE".equals(capability.getStatus()))
            throw new ServiceException("该控制功能当前不可用，请重新打开控制面板");
    }

    private EmsOpenemsCapability findCapability(EmsOpenemsEdge edge, String key)
    {
        LambdaQueryWrapper<EmsOpenemsCapability> query = new LambdaQueryWrapper<EmsOpenemsCapability>()
                .eq(EmsOpenemsCapability::getTenantId, edge.getTenantId())
                .eq(EmsOpenemsCapability::getEndpointId, edge.getEndpointId())
                .eq(EmsOpenemsCapability::getEdgeId, edge.getEdgeId())
                .eq(EmsOpenemsCapability::getDelFlag, "0");
        if (key.startsWith("route:")) query.likeRight(EmsOpenemsCapability::getCapabilityKey, key);
        else query.eq(EmsOpenemsCapability::getCapabilityKey, key);
        return capabilityMapper.selectOne(query.last("limit 1"));
    }

    private EmsOpenemsCapability findActiveCapability(EmsOpenemsEdge edge, String key)
    {
        EmsOpenemsCapability capability = findCapability(edge, key);
        return capability != null && "ACTIVE".equals(capability.getStatus()) ? capability : null;
    }

    private EmsOpenemsCommand existingCommand(Map<String, Object> body, Long tenantId)
    {
        String requestId = body == null || body.get("requestId") == null ? null : String.valueOf(body.get("requestId"));
        if (StringUtils.isEmpty(requestId)) return null;
        return commandMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsCommand>().eq(EmsOpenemsCommand::getTenantId, tenantId)
                .eq(EmsOpenemsCommand::getRequestId, requestId).eq(EmsOpenemsCommand::getDelFlag, "0").last("limit 1"));
    }

    private Map<String, Object> response(EmsOpenemsCommand command)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("requestId", command.getRequestId()); result.put("commandId", command.getId());
        result.put("status", command.getStatus()); result.put("response", command.getResponseJson());
        result.put("request", parseJson(command.getPayloadJson()));
        result.put("responseBody", parseJson(command.getResponseJson()));
        result.put("errorCode", command.getErrorCode()); result.put("errorMessage", command.getErrorMessage());
        return result;
    }

    private Map<String, Object> wrapControlResult(EmsOpenemsDevice device, String operation, String source, String controlKind,
                                                  String componentId, String channelId, Map<String, Object> result)
    {
        Map<String, Object> wrapped = new LinkedHashMap<String, Object>();
        wrapped.put("controlKind", controlKind);
        wrapped.put("operation", operation);
        wrapped.put("operationSource", source);
        wrapped.put("deviceId", device.getId());
        wrapped.put("endpointId", device.getEndpointId());
        wrapped.put("edgeId", device.getEdgeId());
        wrapped.put("componentId", componentId);
        if (StringUtils.isNotEmpty(channelId)) wrapped.put("channelId", channelId);
        wrapped.put("details", result);
        return wrapped;
    }

    private Object parseJson(String value)
    {
        if (StringUtils.isEmpty(value)) return null;
        try
        {
            return JSON.parse(value);
        }
        catch (Exception ex)
        {
            return value;
        }
    }

    private JSONObject object(Object value)
    {
        if (value == null) return new JSONObject();
        if (value instanceof JSONObject) return (JSONObject) value;
        if (value instanceof Map) return JSON.parseObject(JSON.toJSONString(value));
        if (value instanceof String) return JSON.parseObject(String.valueOf(value));
        throw new ServiceException("params必须是JSON对象");
    }

    private String required(Map<String, Object> body, String key, String message)
    {
        String value = body == null || body.get(key) == null ? "" : String.valueOf(body.get(key)).trim();
        if (StringUtils.isEmpty(value)) throw new ServiceException(message);
        return value;
    }

    private String sha256(String value)
    {
        try
        {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        }
        catch (Exception ex) { throw new ServiceException("控制指令hash计算失败"); }
    }
}
