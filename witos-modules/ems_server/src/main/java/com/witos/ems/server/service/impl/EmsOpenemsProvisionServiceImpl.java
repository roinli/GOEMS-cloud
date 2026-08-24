package com.witos.ems.server.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.domain.entity.EmsOpenemsCapability;
import com.witos.ems.server.domain.entity.EmsOpenemsDevice;
import com.witos.ems.server.domain.entity.EmsOpenemsEdge;
import com.witos.ems.server.domain.entity.EmsOpenemsProtocolTemplate;
import com.witos.ems.server.domain.entity.EmsOpenemsProvisionTask;
import com.witos.ems.server.mapper.EmsOpenemsCapabilityMapper;
import com.witos.ems.server.mapper.EmsOpenemsDeviceMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeMapper;
import com.witos.ems.server.mapper.EmsOpenemsProtocolTemplateMapper;
import com.witos.ems.server.mapper.EmsOpenemsProvisionTaskMapper;
import com.witos.ems.server.service.EmsOpenemsBindingService;
import com.witos.ems.server.service.EmsOpenemsProvisionService;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Arrays;

@Service
public class EmsOpenemsProvisionServiceImpl implements EmsOpenemsProvisionService
{
    @Resource private EmsOpenemsEdgeMapper edgeMapper;
    @Resource private EmsOpenemsDeviceMapper deviceMapper;
    @Resource private EmsOpenemsProvisionTaskMapper taskMapper;
    @Resource private EmsOpenemsProtocolTemplateMapper templateMapper;
    @Resource private EmsOpenemsCapabilityMapper capabilityMapper;
    @Resource private EmsOpenemsBindingService bindingService;

    @Override
    public Map<String, Object> preview(Map<String, Object> body)
    {
        EmsOpenemsEdge edge = requireEdge(EmsRequestSupport.requiredLong(body, "edgeId", "必须选择控制器Edge"));
        String displayName = required(body, "displayName", "设备名称不能为空");
        EmsOpenemsProtocolTemplate template = findTemplate(edge, body);
        String templateKind = templateKind(template);
        boolean simulator = "SIMULATOR".equals(templateKind);
        String appId = defaultString(body.get("appId"), template.getAppId());
        if (!simulator && StringUtils.isEmpty(appId))
        {
            throw new ServiceException("必须选择OpenEMS App");
        }
        if ("ONLINE".equals(edge.getOnlineStatus()))
        {
            if (simulator)
            {
                ensureFactoryInstalled(edge, template.getFactoryPid());
            }
            else
            {
                ensureAppAvailable(edge, appId);
                ensureDependencyFactories(edge, body);
            }
        }
        else if (template.getId() == null)
        {
            throw new ServiceException(simulator ? "离线Edge没有该模拟器模板缓存" : "离线Edge没有该App的缓存或基线模板");
        }
        JSONObject parameters = normalizeProperties(template, object(body.get("parameters")));
        validateParameters(template, parameters);
        JSONObject advanced = object(body.get("advancedJson"));
        JSONObject appProperties = normalizeProperties(template, object(body.get("appProperties")));
        appProperties.putAll(parameters);
        if (advanced.get("appProperties") instanceof JSONObject)
            appProperties.putAll(normalizeProperties(template, advanced.getJSONObject("appProperties")));
        JSONObject primaryComponentRule = primaryRule(body, advanced);
        if (simulator && StringUtils.isEmpty(primaryComponentRule.getString("componentId")))
        {
            primaryComponentRule.put("componentId", appProperties.getString("id"));
        }
        if (simulator && StringUtils.isEmpty(primaryComponentRule.getString("componentId")))
        {
            throw new ServiceException("模拟器组件编号不能为空");
        }
        JSONObject desired = new JSONObject();
        desired.put("displayName", displayName);
        desired.put("deviceType", defaultString(body.get("deviceType"), defaultString(template.getDriverType(), appId)));
        desired.put("appId", appId);
        desired.put("appKey", defaultString(body.get("appKey"), null));
        desired.put("appTemplateId", template.getId());
        desired.put("templateKind", templateKind);
        desired.put("driverFactoryPid", defaultString(body.get("driverFactoryPid"), defaultString(template.getFactoryPid(), defaultString(advanced.get("driverFactoryPid"), ""))));
        desired.put("communicationFactoryPid", simulator ? defaultString(template.getFactoryPid(), "") : "");
        desired.put("protocolType", defaultString(template.getProtocolType(), simulator ? "SIMULATOR" : "APP"));
        desired.put("parameters", parameters);
        desired.put("driverProperties", simulator ? appProperties : new JSONObject());
        desired.put("appProperties", appProperties);
        desired.put("parameterMappings", new JSONObject());
        desired.put("primaryComponentRule", primaryComponentRule);
        desired.put("dependencyFactories", dependencyFactories(body, advanced));
        desired.put("advanced", advanced);
        if (advanced != null && !advanced.isEmpty())
        {
            for (Map.Entry<String, Object> entry : advanced.entrySet()) desired.put(entry.getKey(), entry.getValue());
        }
        String normalized = JSON.toJSONString(desired);
        List<Map<String, Object>> changes = new ArrayList<Map<String, Object>>();
        if (simulator)
        {
            changes.add(change("COMPONENT", "CREATE", defaultString(template.getFactoryPid(), appId), appProperties));
        }
        else
        {
            changes.add(change("APP", "ADD_INSTANCE", appId, appProperties));
            changes.add(change("COMPONENT", "VERIFY_PRIMARY", String.valueOf(desired.get("primaryComponentRule")), null));
            changes.add(change("DEPENDENCY", "VALIDATE_FACTORY", "OpenEMS App estimated configuration", desired.get("dependencyFactories")));
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("edgeLocalId", edge.getId());
        result.put("endpointId", edge.getEndpointId());
        result.put("edgeId", edge.getEdgeId());
        result.put("onlineStatus", edge.getOnlineStatus());
        result.put("willCallOpenems", false);
        result.put("desiredConfig", desired);
        result.put("desiredHash", sha256(normalized));
        result.put("changes", changes);
        result.put("initialState", "PENDING_DISPATCH");
        result.put("componentIdSource", simulator ? "SIMULATOR_DIRECT_COMPONENT" : "APP_MANAGER_VERIFIED_PRIMARY");
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(Map<String, Object> body)
    {
        Map<String, Object> preview = preview(body);
        JSONObject desired = JSON.parseObject(JSON.toJSONString(preview.get("desiredConfig")));
        EmsOpenemsEdge edge = requireEdge(((Number) preview.get("edgeLocalId")).longValue());
        Long tenantId = edge.getTenantId();
        String hash = String.valueOf(preview.get("desiredHash"));
        EmsOpenemsProvisionTask duplicate = taskMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsProvisionTask>()
                .eq(EmsOpenemsProvisionTask::getTenantId, tenantId)
                .eq(EmsOpenemsProvisionTask::getDesiredHash, hash)
                .in(EmsOpenemsProvisionTask::getState, "PENDING_DISPATCH", "PRECHECK", "PROVISIONING", "VERIFYING", "ACTIVE")
                .eq(EmsOpenemsProvisionTask::getDelFlag, "0").last("limit 1"));
        if (duplicate != null) throw new ServiceException("相同设备配置已存在或正在下发");
        EmsOpenemsDevice device = new EmsOpenemsDevice();
        device.setTenantId(tenantId);
        device.setEndpointId(edge.getEndpointId());
        device.setEdgeId(edge.getEdgeId());
        device.setResourceGroupId(defaultString(desired.get("resourceGroupId"), "ems-" + hash.substring(0, 16)));
        device.setPrimaryComponentId(primaryComponentId(desired));
        device.setDeviceType(desired.getString("deviceType"));
        device.setDisplayName(desired.getString("displayName"));
        device.setCompanyId(edge.getCompanyId());
        device.setStationId(edge.getStationId());
        device.setSourceType("EMS_CREATED");
        device.setStatus("PENDING_DISPATCH");
        device.setDesiredConfigHash(hash);
        device.setDesiredConfigJson(desired.toJSONString());
        device.setRawJson(null);
        device.setDelFlag("0");
        device.setCreateTime(new Date());
        device.setUpdateTime(new Date());
        deviceMapper.insert(device);
        bindingService.inheritNewDevice(device.getId(), new Date(), "INHERITED");
        EmsOpenemsProvisionTask task = new EmsOpenemsProvisionTask();
        task.setTenantId(tenantId);
        task.setDeviceId(device.getId());
        task.setEndpointId(edge.getEndpointId());
        task.setEdgeId(edge.getEdgeId());
        task.setDesiredHash(hash);
        task.setState("PENDING_DISPATCH");
        task.setStep("WAIT_EDGE_ONLINE");
        task.setAttempt(0);
        task.setDesiredJson(desired.toJSONString());
        task.setDelFlag("0");
        task.setCreateTime(new Date());
        task.setUpdateTime(new Date());
        taskMapper.insert(task);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("deviceId", device.getId());
        result.put("taskId", task.getId());
        result.put("state", task.getState());
        result.put("onlineStatus", edge.getOnlineStatus());
        result.put("automaticDispatch", true);
        return result;
    }

    @Override
    public Map<String, Object> getTask(Long id)
    {
        EmsOpenemsProvisionTask task = taskMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsProvisionTask>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsProvisionTask::getTenantId,
                        EmsRequestSupport.currentTenantId())
                .eq(EmsOpenemsProvisionTask::getId, id).eq(EmsOpenemsProvisionTask::getDelFlag, "0").last("limit 1"));
        if (task == null) throw new ServiceException("设备下发任务不存在或不属于当前租户");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", task.getId());
        result.put("deviceId", task.getDeviceId());
        result.put("state", task.getState());
        result.put("step", task.getStep());
        result.put("attempt", task.getAttempt());
        result.put("lastError", task.getLastError());
        result.put("componentId", task.getComponentId());
        result.put("bridgeId", task.getBridgeId());
        result.put("conflictDetail", task.getConflictDetail());
        result.put("desiredConfig", StringUtils.isEmpty(task.getDesiredJson()) ? null : JSON.parse(task.getDesiredJson()));
        result.put("verify", StringUtils.isEmpty(task.getVerifyJson()) ? null : JSON.parse(task.getVerifyJson()));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> retry(Long id, Map<String, Object> body)
    {
        EmsOpenemsProvisionTask task = taskMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsProvisionTask>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsProvisionTask::getTenantId,
                        EmsRequestSupport.currentTenantId())
                .eq(EmsOpenemsProvisionTask::getId, id)
                .eq(EmsOpenemsProvisionTask::getDelFlag, "0").last("limit 1"));
        if (task == null) throw new ServiceException("设备下发任务不存在或不属于当前租户");
        Long tenantId = task.getTenantId();
        if (!Arrays.asList("UNSUPPORTED", "CONFLICT", "PARTIAL_FAILED", "FAILED", "TIMEOUT_UNKNOWN").contains(task.getState()))
        {
            throw new ServiceException("只有已停止的失败任务可以修改后重新提交");
        }
        Map<String, Object> preview = preview(body);
        EmsOpenemsEdge edge = requireEdge(((Number) preview.get("edgeLocalId")).longValue());
        if (!tenantId.equals(edge.getTenantId()) || !edge.getEndpointId().equals(task.getEndpointId())
                || !edge.getEdgeId().equals(task.getEdgeId()))
        {
            throw new ServiceException("重新提交不能更换目标Edge");
        }
        String hash = String.valueOf(preview.get("desiredHash"));
        EmsOpenemsProvisionTask duplicate = taskMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsProvisionTask>()
                .eq(EmsOpenemsProvisionTask::getTenantId, tenantId)
                .eq(EmsOpenemsProvisionTask::getDesiredHash, hash)
                .ne(EmsOpenemsProvisionTask::getId, id)
                .in(EmsOpenemsProvisionTask::getState, "PENDING_DISPATCH", "PRECHECK", "PROVISIONING", "VERIFYING", "ACTIVE")
                .eq(EmsOpenemsProvisionTask::getDelFlag, "0").last("limit 1"));
        if (duplicate != null) throw new ServiceException("相同设备配置已存在或正在下发");
        EmsOpenemsDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsDevice>()
                .eq(EmsOpenemsDevice::getTenantId, tenantId).eq(EmsOpenemsDevice::getId, task.getDeviceId())
                .eq(EmsOpenemsDevice::getDelFlag, "0").last("limit 1"));
        if (device == null) throw new ServiceException("本地下发设备不存在或不属于当前租户");

        JSONObject desired = JSON.parseObject(JSON.toJSONString(preview.get("desiredConfig")));
        device.setDisplayName(desired.getString("displayName"));
        device.setDeviceType(desired.getString("deviceType"));
        device.setPrimaryComponentId(primaryComponentId(desired));
        device.setDesiredConfigHash(hash);
        device.setDesiredConfigJson(desired.toJSONString());
        device.setStatus("PENDING_DISPATCH");
        device.setUpdateTime(new Date());
        deviceMapper.updateById(device);

        task.setDesiredHash(hash);
        task.setDesiredJson(desired.toJSONString());
        task.setState("PENDING_DISPATCH");
        task.setStep("WAIT_EDGE_ONLINE");
        task.setAttempt(0);
        task.setLastError(null);
        task.setComponentId(null);
        task.setBridgeId(null);
        task.setConflictDetail(null);
        task.setVerifyJson(null);
        task.setStartedAt(null);
        task.setFinishedAt(null);
        task.setUpdateTime(new Date());
        taskMapper.updateById(task);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("deviceId", device.getId());
        result.put("taskId", task.getId());
        result.put("state", task.getState());
        result.put("onlineStatus", edge.getOnlineStatus());
        result.put("automaticDispatch", true);
        return result;
    }

    private EmsOpenemsProtocolTemplate findTemplate(EmsOpenemsEdge edge, Map<String, Object> body)
    {
        Long templateId = EmsRequestSupport.asLong(body.get("templateId"));
        if (templateId != null)
        {
            EmsOpenemsProtocolTemplate row = templateMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsProtocolTemplate>()
                    .eq(EmsOpenemsProtocolTemplate::getTenantId, edge.getTenantId())
                    .eq(EmsOpenemsProtocolTemplate::getEndpointId, edge.getEndpointId())
                    .eq(EmsOpenemsProtocolTemplate::getId, templateId)
                    .eq(EmsOpenemsProtocolTemplate::getDelFlag, "0").last("limit 1"));
            if (row != null) return row;
        }
        String appId = defaultString(body.get("appId"), null);
        if (!StringUtils.isEmpty(appId))
        {
            EmsOpenemsProtocolTemplate row = templateMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsProtocolTemplate>()
                    .eq(EmsOpenemsProtocolTemplate::getTenantId, edge.getTenantId())
                    .eq(EmsOpenemsProtocolTemplate::getEndpointId, edge.getEndpointId())
                    .eq(EmsOpenemsProtocolTemplate::getAppId, appId)
                    .eq(EmsOpenemsProtocolTemplate::getEnabled, "0").eq(EmsOpenemsProtocolTemplate::getDelFlag, "0").last("limit 1"));
            if (row != null) return row;
            if (!baselineApp(appId)) throw new ServiceException("目标Edge没有该OpenEMS App模板");
            EmsOpenemsProtocolTemplate baseline = new EmsOpenemsProtocolTemplate();
            baseline.setFactoryPid("");
            baseline.setAppId(appId);
            baseline.setProtocolType(protocolFromApp(appId));
            baseline.setSchemaJson("{\"fields\":[{\"key\":\"alias\",\"label\":\"别名\",\"type\":\"string\",\"required\":false}]}");
            baseline.setDefaultJson("{}");
            baseline.setAdaptationStatus(baselineAdapted(appId) ? "ADAPTED" : "AUTO_GENERATED");
            baseline.setDriverType(appId);
            baseline.setCommunicationType("APP");
            return baseline;
        }
        throw new ServiceException("必须选择OpenEMS模板");
    }

    private void validateParameters(EmsOpenemsProtocolTemplate template, JSONObject parameters)
    {
        JSONObject schema = StringUtils.isEmpty(template.getSchemaJson()) ? new JSONObject() : JSON.parseObject(template.getSchemaJson());
        JSONArray fields = schema.getJSONArray("fields");
        if (fields == null) return;
        for (Object value : fields)
        {
            JSONObject field = value instanceof JSONObject ? (JSONObject) value : JSON.parseObject(JSON.toJSONString(value));
            String key = fieldKey(field);
            boolean required = field.getBooleanValue("required") || field.getBooleanValue("isRequired");
            if (required && StringUtils.isEmpty(field.getString("showIf"))
                    && (parameters == null || parameters.get(key) == null || String.valueOf(parameters.get(key)).trim().isEmpty()))
            {
                throw new ServiceException("协议参数不能为空：" + key);
            }
        }
    }

    private JSONObject normalizeProperties(EmsOpenemsProtocolTemplate template, JSONObject source)
    {
        if (source == null || source.isEmpty()) return new JSONObject();
        Map<String, String> aliases = propertyAliases(template);
        JSONObject result = new JSONObject();
        for (Map.Entry<String, Object> entry : source.entrySet())
        {
            String canonical = aliases.get(String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT));
            if (canonical != null && canonical.equals(entry.getKey())) result.put(canonical, entry.getValue());
        }
        for (Map.Entry<String, Object> entry : source.entrySet())
        {
            String sourceKey = String.valueOf(entry.getKey());
            String canonical = aliases.get(sourceKey.toLowerCase(Locale.ROOT));
            if (StringUtils.isEmpty(canonical)) canonical = sourceKey;
            String existing = caseInsensitiveKey(result, canonical);
            if (existing == null) result.put(canonical, entry.getValue());
        }
        return result;
    }

    private Map<String, String> propertyAliases(EmsOpenemsProtocolTemplate template)
    {
        Map<String, String> result = new LinkedHashMap<String, String>();
        JSONObject schema = StringUtils.isEmpty(template.getSchemaJson()) ? new JSONObject() : JSON.parseObject(template.getSchemaJson());
        JSONArray fields = schema.getJSONArray("fields");
        if (fields == null) return result;
        for (Object value : fields)
        {
            JSONObject field = value instanceof JSONObject ? (JSONObject) value : JSON.parseObject(JSON.toJSONString(value));
            String canonical = fieldKey(field);
            if (StringUtils.isEmpty(canonical)) continue;
            addPropertyAlias(result, canonical, canonical);
            addPropertyAlias(result, field.getString("id"), canonical);
            addPropertyAlias(result, field.getString("key"), canonical);
            addPropertyAlias(result, field.getString("name"), canonical);
            addPropertyAlias(result, field.getString("label"), canonical);
        }
        return result;
    }

    private void addPropertyAlias(Map<String, String> aliases, String alias, String canonical)
    {
        if (!StringUtils.isEmpty(alias)) aliases.put(alias.toLowerCase(Locale.ROOT), canonical);
    }

    private String fieldKey(JSONObject field)
    {
        return defaultString(field.getString("id"), defaultString(field.getString("key"), field.getString("name")));
    }

    private String caseInsensitiveKey(JSONObject object, String key)
    {
        for (String existing : object.keySet()) if (existing.equalsIgnoreCase(key)) return existing;
        return null;
    }

    private String primaryComponentId(JSONObject desired)
    {
        JSONObject rule = desired.getJSONObject("primaryComponentRule");
        return rule == null ? "" : defaultString(rule.getString("componentId"), "");
    }

    private void ensureAppAvailable(EmsOpenemsEdge edge, String appId)
    {
        if (StringUtils.isEmpty(appId))
        {
            throw new ServiceException("必须选择OpenEMS App");
        }
        Long count = templateMapper.selectCount(new LambdaQueryWrapper<EmsOpenemsProtocolTemplate>()
                .eq(EmsOpenemsProtocolTemplate::getTenantId, edge.getTenantId())
                .eq(EmsOpenemsProtocolTemplate::getEndpointId, edge.getEndpointId())
                .eq(EmsOpenemsProtocolTemplate::getAppId, appId)
                .eq(EmsOpenemsProtocolTemplate::getEnabled, "0")
                .eq(EmsOpenemsProtocolTemplate::getDelFlag, "0"));
        if (count == null || count == 0) throw new ServiceException("目标Edge未安装App：" + appId);
    }

    private void ensureDependencyFactories(EmsOpenemsEdge edge, Map<String, Object> body)
    {
        JSONArray factories = dependencyFactories(body, object(body == null ? null : body.get("advancedJson")));
        for (Object item : factories) ensureFactoryInstalled(edge, String.valueOf(item));
    }

    private void ensureFactoryInstalled(EmsOpenemsEdge edge, String factoryPid)
    {
        if (StringUtils.isEmpty(factoryPid)) throw new ServiceException("Factory PID不能为空");
        Long count = capabilityMapper.selectCount(new LambdaQueryWrapper<EmsOpenemsCapability>()
                .eq(EmsOpenemsCapability::getTenantId, edge.getTenantId())
                .eq(EmsOpenemsCapability::getEndpointId, edge.getEndpointId())
                .eq(EmsOpenemsCapability::getEdgeId, edge.getEdgeId())
                .eq(EmsOpenemsCapability::getCapabilityKey, "factory:" + factoryPid)
                .eq(EmsOpenemsCapability::getStatus, "ACTIVE").eq(EmsOpenemsCapability::getDelFlag, "0"));
        if (count == null || count == 0) throw new ServiceException("目标Edge未安装Factory：" + factoryPid);
    }

    private Map<String, Object> change(String resourceType, String action, String target, Object detail)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("resourceType", resourceType); row.put("action", action); row.put("target", target); row.put("detail", detail);
        return row;
    }

    private EmsOpenemsEdge requireEdge(Long id)
    {
        EmsOpenemsEdge edge = edgeMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsEdge>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsEdge::getTenantId,
                        EmsRequestSupport.currentTenantId())
                .eq(EmsOpenemsEdge::getId, id)
                .eq(EmsOpenemsEdge::getDelFlag, "0").last("limit 1"));
        if (edge == null) throw new ServiceException("OpenEMS Edge不存在或不属于当前租户");
        return edge;
    }

    private JSONObject object(Object value)
    {
        if (value == null) return new JSONObject();
        if (value instanceof JSONObject) return (JSONObject) value;
        if (value instanceof Map) return JSON.parseObject(JSON.toJSONString(value));
        if (value instanceof String && !StringUtils.isEmpty(String.valueOf(value)))
        {
            try { return JSON.parseObject(String.valueOf(value)); } catch (RuntimeException ex) { throw new ServiceException("高级JSON格式无效"); }
        }
        throw new ServiceException("配置参数必须是JSON对象");
    }

    private JSONObject primaryRule(Map<String, Object> body, JSONObject advanced)
    {
        JSONObject rule = new JSONObject();
        Object source = body.get("primaryComponentRule") != null ? body.get("primaryComponentRule") : advanced.get("primaryComponentRule");
        if (source instanceof JSONObject) rule.putAll((JSONObject) source);
        else if (source instanceof Map) rule.putAll(JSON.parseObject(JSON.toJSONString(source)));
        else if (source instanceof String && !StringUtils.isEmpty(String.valueOf(source))) rule.put("componentId", source);
        if (StringUtils.isEmpty(rule.getString("componentId")) && body.get("primaryComponentId") != null)
            rule.put("componentId", String.valueOf(body.get("primaryComponentId")));
        if (StringUtils.isEmpty(rule.getString("factoryPid")) && body.get("driverFactoryPid") != null)
            rule.put("factoryPid", String.valueOf(body.get("driverFactoryPid")));
        return rule;
    }

    private JSONArray dependencyFactories(Map<String, Object> body, JSONObject advanced)
    {
        Object value = body.get("dependencyFactories") != null ? body.get("dependencyFactories") : advanced.get("dependencyFactories");
        if (value instanceof JSONArray) return (JSONArray) value;
        if (value instanceof List) return JSON.parseArray(JSON.toJSONString(value));
        JSONArray result = new JSONArray();
        if (value instanceof String && !StringUtils.isEmpty(String.valueOf(value)))
            for (String item : String.valueOf(value).split(",")) if (!StringUtils.isEmpty(item.trim())) result.add(item.trim());
        return result;
    }

    private boolean baselineApp(String appId)
    {
        String value = appId == null ? "" : appId.toLowerCase();
        return value.contains("modbus") || value.contains("mqtt") || value.contains("mbus")
                || value.contains("onewire") || value.contains("one-wire") || value.contains("simulator");
    }

    private boolean baselineAdapted(String appId)
    {
        return baselineApp(appId);
    }

    private String protocolFromApp(String appId)
    {
        String value = appId == null ? "" : appId.toLowerCase();
        if (value.contains("modbus") && value.contains("tcp")) return "MODBUS_TCP";
        if (value.contains("modbus") || value.contains("serial")) return "MODBUS_SERIAL";
        if (value.contains("mqtt")) return "MQTT";
        if (value.contains("mbus")) return "MBUS";
        if (value.contains("onewire") || value.contains("one-wire")) return "ONEWIRE";
        if (value.contains("simulator")) return "SIMULATOR";
        return "APP";
    }

    private String templateKind(EmsOpenemsProtocolTemplate template)
    {
        if (template == null) return "APP";
        if ("SIMULATOR".equals(template.getProtocolType()) || (template.getFactoryPid() != null && template.getFactoryPid().startsWith("Simulator.")))
        {
            return "SIMULATOR";
        }
        if (!StringUtils.isEmpty(template.getAppId())) return "APP";
        return "FACTORY_COMPAT";
    }

    private String required(Map<String, Object> body, String key, String message)
    {
        String value = body == null || body.get(key) == null ? null : String.valueOf(body.get(key)).trim();
        if (StringUtils.isEmpty(value)) throw new ServiceException(message);
        return value;
    }

    private String defaultString(Object value, String fallback)
    {
        return value == null || StringUtils.isEmpty(String.valueOf(value)) ? fallback : String.valueOf(value);
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
        catch (Exception ex) { throw new ServiceException("设备配置hash计算失败"); }
    }
}
