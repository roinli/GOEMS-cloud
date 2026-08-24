package com.witos.ems.server.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.domain.entity.EmsOpenemsAppComponent;
import com.witos.ems.server.domain.entity.EmsOpenemsAppInstance;
import com.witos.ems.server.domain.entity.EmsOpenemsAppRelation;
import com.witos.ems.server.domain.entity.EmsOpenemsCapability;
import com.witos.ems.server.domain.entity.EmsOpenemsComponent;
import com.witos.ems.server.domain.entity.EmsOpenemsComponentRelation;
import com.witos.ems.server.domain.entity.EmsOpenemsEdge;
import com.witos.ems.server.domain.entity.EmsOpenemsProtocolTemplate;
import com.witos.ems.server.mapper.EmsOpenemsAppComponentMapper;
import com.witos.ems.server.mapper.EmsOpenemsAppInstanceMapper;
import com.witos.ems.server.mapper.EmsOpenemsAppRelationMapper;
import com.witos.ems.server.mapper.EmsOpenemsCapabilityMapper;
import com.witos.ems.server.mapper.EmsOpenemsComponentMapper;
import com.witos.ems.server.mapper.EmsOpenemsComponentRelationMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeMapper;
import com.witos.ems.server.mapper.EmsOpenemsProtocolTemplateMapper;
import com.witos.ems.server.openems.OpenemsJsonRpcClient;
import com.witos.ems.server.service.EmsOpenemsCapabilityService;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class EmsOpenemsCapabilityServiceImpl implements EmsOpenemsCapabilityService
{
    private static final String BASELINE = "openems-local-20260808";

    @Resource private EmsOpenemsEdgeMapper edgeMapper;
    @Resource private EmsOpenemsComponentMapper componentMapper;
    @Resource private EmsOpenemsComponentRelationMapper componentRelationMapper;
    @Resource private EmsOpenemsAppInstanceMapper appMapper;
    @Resource private EmsOpenemsAppComponentMapper appComponentMapper;
    @Resource private EmsOpenemsAppRelationMapper appRelationMapper;
    @Resource private EmsOpenemsCapabilityMapper capabilityMapper;
    @Resource private EmsOpenemsProtocolTemplateMapper templateMapper;
    @Resource private OpenemsJsonRpcClient jsonRpcClient;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> refresh(Long edgeLocalId)
    {
        EmsOpenemsEdge edge = requireEdge(edgeLocalId);
        if (!"ONLINE".equals(edge.getOnlineStatus()))
        {
            throw new ServiceException("Edge离线，不能刷新实时能力；可以继续使用最近缓存或基线模板");
        }
        Map<String, Object> snapshot = jsonRpcClient.getCapabilitySnapshot(edge.getEndpointId(), edge.getEdgeId());
        JSONObject root = JSON.parseObject(JSON.toJSONString(snapshot));
        JSONObject routes = root.getJSONObject("routes");
        JSONObject edgeConfig = root.getJSONObject("edgeConfig");
        Date now = new Date();
        Set<String> seen = new HashSet<String>();
        int routeCount = syncRoutes(edge, routes, now, seen);
        int factoryCount = syncFactories(edge, edgeConfig, now, seen);
        int channelCount = syncChannels(edge, edgeConfig, now, seen);
        int appTemplateCount = syncAppTemplates(edge, now);
        markMissing(edge, seen, now);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("edgeId", edge.getEdgeId());
        result.put("routeCount", routeCount);
        result.put("factoryCount", factoryCount);
        result.put("channelCount", channelCount);
        result.put("appTemplateCount", appTemplateCount);
        result.put("lastSeenAt", now);
        result.put("templates", templates(edgeLocalId).get("records"));
        return result;
    }

    private int syncAppTemplates(EmsOpenemsEdge edge, Date now)
    {
        Map<String, Object> snapshot = jsonRpcClient.getAppSnapshot(edge.getEndpointId(), edge.getEdgeId());
        JSONObject snapshotJson = JSON.parseObject(JSON.toJSONString(snapshot));
        JSONArray apps = snapshotJson == null ? null : snapshotJson.getJSONArray("apps");
        if (apps == null) return 0;
        int count = 0;
        for (Object item : apps)
        {
            JSONObject app = item instanceof JSONObject ? (JSONObject) item : JSON.parseObject(JSON.toJSONString(item));
            String appId = app.getString("appId");
            if (StringUtils.isEmpty(appId)) continue;
            JSONObject assistant = null;
            String assistantError = null;
            if ("INSTALLABLE".equals(appStatus(app)))
            {
                try
                {
                    Map<String, Object> params = new LinkedHashMap<String, Object>();
                    params.put("appId", appId);
                    assistant = JSON.parseObject(JSON.toJSONString(jsonRpcClient.componentJsonApi(
                            edge.getEndpointId(), edge.getEdgeId(), "_appManager", "getAppAssistant", params)));
                }
                catch (RuntimeException ex)
                {
                    assistantError = ex.getMessage();
                }
            }
            upsertAppTemplate(edge, appId, app, assistant, assistantError, now);
            count++;
        }
        return count;
    }

    private void upsertAppTemplate(EmsOpenemsEdge edge, String appId, JSONObject app, JSONObject assistant,
                                   String assistantError, Date now)
    {
        EmsOpenemsProtocolTemplate row = templateMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsProtocolTemplate>()
                .eq(EmsOpenemsProtocolTemplate::getTenantId, edge.getTenantId())
                .eq(EmsOpenemsProtocolTemplate::getEndpointId, edge.getEndpointId())
                .eq(EmsOpenemsProtocolTemplate::getAppId, appId)
                .eq(EmsOpenemsProtocolTemplate::getBaselineCommit, BASELINE)
                .eq(EmsOpenemsProtocolTemplate::getDelFlag, "0")
                .last("limit 1"));
        if (row == null)
        {
            row = new EmsOpenemsProtocolTemplate();
            row.setTenantId(edge.getTenantId());
            row.setEndpointId(edge.getEndpointId());
            row.setFactoryPid("");
            row.setAppId(appId);
            row.setBaselineCommit(BASELINE);
            row.setDelFlag("0");
            row.setCreateTime(now);
        }
        JSONObject schema = new JSONObject();
        JSONArray fields = appFields(assistant);
        schema.put("fields", fields);
        schema.put("app", app);
        schema.put("assistantAvailable", assistant != null);
        if (assistant != null) schema.put("assistant", assistant);
        if (!StringUtils.isEmpty(assistantError)) schema.put("assistantError", assistantError);
        JSONObject defaults = new JSONObject();
        for (Object value : fields)
        {
            JSONObject field = (JSONObject) value;
            if (field.containsKey("defaultValue")) defaults.put(field.getString("key"), field.get("defaultValue"));
        }
        row.setProtocolType(protocolFromApp(appId));
        row.setDriverType(defaultString(app.get("name"), appId));
        row.setCommunicationType("APP");
        row.setSchemaJson(schema.toJSONString());
        row.setDefaultJson(defaults.toJSONString());
        row.setAdaptationStatus(adaptedApp(appId) ? "ADAPTED" : "AUTO_GENERATED");
        row.setEnabled("0");
        row.setUpdateTime(now);
        if (row.getId() == null) templateMapper.insert(row); else templateMapper.updateById(row);
    }

    private String appStatus(JSONObject app)
    {
        JSONObject status = app == null ? null : app.getJSONObject("status");
        return status == null ? null : status.getString("name");
    }

    private JSONArray appFields(JSONObject assistant)
    {
        JSONArray result = new JSONArray();
        if (assistant == null) return result;
        collectAppFields(assistant.getJSONArray("fields"), result);
        return result;
    }

    private void collectAppFields(JSONArray source, JSONArray target)
    {
        if (source == null) return;
        for (Object value : source)
        {
            JSONObject raw = value instanceof JSONObject ? (JSONObject) value
                    : JSON.parseObject(JSON.toJSONString(value));
            if (raw == null) continue;
            collectAppFields(raw.getJSONArray("fieldGroup"), target);
            JSONObject options = raw.getJSONObject("templateOptions");
            if (options == null) options = raw.getJSONObject("props");
            if (options != null) collectAppFields(options.getJSONArray("fields"), target);

            String key = raw.getString("key");
            if (StringUtils.isEmpty(key) || "ALIAS".equalsIgnoreCase(key) || raw.getBooleanValue("hide")) continue;
            JSONObject field = new JSONObject();
            field.put("key", key);
            field.put("label", defaultString(options == null ? null : options.get("label"), key));
            field.put("type", appFieldType(raw, options));
            field.put("required", options != null && options.getBooleanValue("required"));
            if (raw.containsKey("defaultValue")) field.put("defaultValue", raw.get("defaultValue"));
            copyIfPresent(options, field, "description", "placeholder", "unit", "min", "max", "minLength",
                    "maxLength", "step", "multiple", "readonly", "disabled", "pattern");
            JSONArray normalizedOptions = appFieldOptions(options == null ? null : options.getJSONArray("options"));
            if (!normalizedOptions.isEmpty()) field.put("options", normalizedOptions);
            JSONObject expressions = raw.getJSONObject("expressionProperties");
            if (expressions != null)
            {
                String showIf = expressions.getString("templateOptions.required");
                if (StringUtils.isEmpty(showIf)) showIf = positiveShowExpression(expressions.getString("hide"));
                if (!StringUtils.isEmpty(showIf)) field.put("showIf", showIf);
            }
            field.put("sourceType", raw.getString("type"));
            target.add(field);
        }
    }

    private String appFieldType(JSONObject raw, JSONObject options)
    {
        String type = defaultString(raw.getString("type"), "input").toLowerCase();
        String inputType = options == null ? null : options.getString("type");
        if ("checkbox".equals(type) || "toggle".equals(type)) return "boolean";
        if ("select".equals(type) || "radio".equals(type) || type.contains("select") || type.contains("picker"))
            return "select";
        if ("range".equals(type) || "number".equalsIgnoreCase(inputType)) return "number";
        if ("password".equalsIgnoreCase(inputType)) return "password";
        if ("textarea".equals(type)) return "textarea";
        return "string";
    }

    private JSONArray appFieldOptions(JSONArray source)
    {
        JSONArray result = new JSONArray();
        if (source == null) return result;
        for (Object value : source)
        {
            if (value instanceof JSONObject)
            {
                JSONObject option = (JSONObject) value;
                JSONArray children = option.getJSONArray("options");
                if (children != null)
                {
                    result.addAll(appFieldOptions(children));
                    continue;
                }
                if (option.containsKey("value"))
                {
                    JSONObject normalized = new JSONObject();
                    normalized.put("label", defaultString(option.get("label"), String.valueOf(option.get("value"))));
                    normalized.put("value", option.get("value"));
                    result.add(normalized);
                }
            }
            else
            {
                JSONObject normalized = new JSONObject();
                normalized.put("label", String.valueOf(value));
                normalized.put("value", value);
                result.add(normalized);
            }
        }
        return result;
    }

    private void copyIfPresent(JSONObject source, JSONObject target, String... keys)
    {
        if (source == null) return;
        for (String key : keys) if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private String positiveShowExpression(String hideExpression)
    {
        if (StringUtils.isEmpty(hideExpression)) return null;
        String value = hideExpression.trim();
        if (value.startsWith("!(") && value.endsWith(")")) return value.substring(2, value.length() - 1);
        return null;
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

    private boolean adaptedApp(String appId)
    {
        String protocol = protocolFromApp(appId);
        return "MODBUS_TCP".equals(protocol) || "MODBUS_SERIAL".equals(protocol) || "MQTT".equals(protocol)
                || "MBUS".equals(protocol) || "ONEWIRE".equals(protocol) || "SIMULATOR".equals(protocol);
    }

    private int syncRoutes(EmsOpenemsEdge edge, JSONObject routes, Date now, Set<String> seen)
    {
        JSONArray endpoints = routes == null ? null : routes.getJSONArray("endpoints");
        if (endpoints == null)
        {
            return 0;
        }
        String version = routes.getString("version");
        int count = 0;
        for (Object item : endpoints)
        {
            JSONObject endpoint = item instanceof JSONObject ? (JSONObject) item : JSON.parseObject(JSON.toJSONString(item));
            String method = endpoint.getString("method");
            if (StringUtils.isEmpty(method))
            {
                continue;
            }
            String key = "route:" + method + ":" + count;
            EmsOpenemsCapability row = capability(edge, "", key);
            row.setRoute(method);
            row.setRequestSchema(json(endpoint.get("request")));
            row.setResponseSchema(json(endpoint.get("response")));
            row.setGuards(json(endpoint.get("guards")));
            row.setVersion(version);
            save(row, now);
            seen.add(key);
            count++;
        }
        return count;
    }

    private int syncFactories(EmsOpenemsEdge edge, JSONObject edgeConfig, Date now, Set<String> seen)
    {
        JSONObject factories = edgeConfig == null ? null : edgeConfig.getJSONObject("factories");
        if (factories == null)
        {
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, Object> entry : factories.entrySet())
        {
            String key = "factory:" + entry.getKey();
            EmsOpenemsCapability row = capability(edge, "", key);
            row.setFactorySchema(json(entry.getValue()));
            save(row, now);
            seen.add(key);
            upsertTemplate(edge, entry.getKey(), entry.getValue(), now);
            count++;
        }
        return count;
    }

    private int syncChannels(EmsOpenemsEdge edge, JSONObject edgeConfig, Date now, Set<String> seen)
    {
        JSONObject components = edgeConfig == null ? null : edgeConfig.getJSONObject("components");
        if (components == null)
        {
            return 0;
        }
        int count = 0;
        for (Map.Entry<String, Object> componentEntry : components.entrySet())
        {
            JSONObject component = componentEntry.getValue() instanceof JSONObject
                    ? (JSONObject) componentEntry.getValue() : JSON.parseObject(JSON.toJSONString(componentEntry.getValue()));
            JSONObject channels = component.getJSONObject("channels");
            if (channels == null)
            {
                continue;
            }
            for (Map.Entry<String, Object> channel : channels.entrySet())
            {
                String key = "channel:" + componentEntry.getKey() + "/" + channel.getKey();
                EmsOpenemsCapability row = capability(edge, componentEntry.getKey(), key);
                row.setChannelSchema(json(channel.getValue()));
                save(row, now);
                seen.add(key);
                count++;
            }
        }
        return count;
    }

    private void upsertTemplate(EmsOpenemsEdge edge, String factoryPid, Object factorySchema, Date now)
    {
        TemplateDefinition definition = standard(factoryPid, factorySchema);
        EmsOpenemsProtocolTemplate row = templateMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsProtocolTemplate>()
                .eq(EmsOpenemsProtocolTemplate::getTenantId, edge.getTenantId())
                .eq(EmsOpenemsProtocolTemplate::getEndpointId, edge.getEndpointId())
                .eq(EmsOpenemsProtocolTemplate::getFactoryPid, factoryPid)
                .eq(EmsOpenemsProtocolTemplate::getAppId, "")
                .eq(EmsOpenemsProtocolTemplate::getProtocolType, definition.protocolType)
                .eq(EmsOpenemsProtocolTemplate::getBaselineCommit, BASELINE)
                .eq(EmsOpenemsProtocolTemplate::getDelFlag, "0")
                .last("limit 1"));
        if (row == null)
        {
            row = new EmsOpenemsProtocolTemplate();
            row.setTenantId(edge.getTenantId());
            row.setEndpointId(edge.getEndpointId());
            row.setFactoryPid(factoryPid);
            row.setAppId("");
            row.setBaselineCommit(BASELINE);
            row.setDelFlag("0");
            row.setCreateTime(now);
        }
        row.setProtocolType(definition.protocolType);
        row.setDriverType(factoryPid);
        row.setCommunicationType(definition.communicationType);
        row.setSchemaJson(definition.schema.toJSONString());
        row.setDefaultJson(definition.defaults.toJSONString());
        row.setAdaptationStatus(definition.status);
        row.setEnabled("0");
        row.setUpdateTime(now);
        if (row.getId() == null) templateMapper.insert(row); else templateMapper.updateById(row);
    }

    private TemplateDefinition standard(String factoryPid, Object actualFactorySchema)
    {
        if ("Bridge.Modbus.Tcp".equals(factoryPid))
        {
            return template("MODBUS_TCP", "TCP", fields(
                    field("ip", "IP地址", "string", true, null),
                    field("port", "端口", "integer", true, 502)));
        }
        if ("Bridge.Modbus.Serial".equals(factoryPid))
        {
            return template("MODBUS_SERIAL", "SERIAL", fields(
                    field("portName", "串口", "string", true, "/dev/ttyUSB0"),
                    field("baudRate", "波特率", "integer", true, 9600),
                    field("databits", "数据位", "integer", true, 8),
                    field("stopbits", "停止位", "string", true, "ONE"),
                    field("parity", "校验位", "string", true, "NONE")));
        }
        if ("Bridge.Mqtt".equals(factoryPid))
        {
            return template("MQTT", "TCP", fields(
                    field("host", "Broker地址", "string", true, "localhost"),
                    field("port", "端口", "integer", true, 1883),
                    field("secureConnect", "TLS", "boolean", false, false),
                    field("username", "用户名", "string", false, ""),
                    field("password", "密码", "password", false, "")));
        }
        if ("Bridge.Mbus".equals(factoryPid))
        {
            return template("MBUS", "SERIAL", fields(
                    field("portName", "串口", "string", true, "/dev/ttyUSB0"),
                    field("baudrate", "波特率", "integer", true, 2400)));
        }
        if ("Bridge.Onewire".equals(factoryPid))
        {
            return template("ONEWIRE", "ONEWIRE", fields(field("port", "适配器端口", "string", true, "USB1")));
        }
        if (factoryPid != null && factoryPid.startsWith("Simulator."))
        {
            JSONObject actual = actualFactorySchema instanceof JSONObject
                    ? (JSONObject) actualFactorySchema : JSON.parseObject(JSON.toJSONString(actualFactorySchema));
            JSONArray properties = actual == null ? null : actual.getJSONArray("properties");
            TemplateDefinition result = new TemplateDefinition();
            result.protocolType = "SIMULATOR";
            result.communicationType = "SIMULATOR";
            result.schema = new JSONObject();
            result.schema.put("fields", properties == null ? new JSONArray() : properties);
            result.defaults = new JSONObject();
            result.status = properties == null || properties.isEmpty() ? "AUTO_GENERATED" : "ADAPTED";
            return result;
        }
        JSONObject actual = actualFactorySchema instanceof JSONObject
                ? (JSONObject) actualFactorySchema : JSON.parseObject(JSON.toJSONString(actualFactorySchema));
        JSONArray properties = actual == null ? null : actual.getJSONArray("properties");
        TemplateDefinition result = new TemplateDefinition();
        result.protocolType = "OTHER";
        result.communicationType = "FACTORY";
        result.schema = new JSONObject();
        result.schema.put("fields", properties == null ? new JSONArray() : properties);
        result.defaults = new JSONObject();
        result.status = properties == null || properties.isEmpty() ? "ADVANCED_JSON" : "AUTO_GENERATED";
        return result;
    }

    private TemplateDefinition template(String protocol, String communication, JSONArray fields)
    {
        TemplateDefinition result = new TemplateDefinition();
        result.protocolType = protocol;
        result.communicationType = communication;
        result.schema = new JSONObject();
        result.schema.put("fields", fields);
        result.defaults = new JSONObject();
        for (Object value : fields)
        {
            JSONObject field = (JSONObject) value;
            if (field.containsKey("defaultValue")) result.defaults.put(field.getString("key"), field.get("defaultValue"));
        }
        result.status = "ADAPTED";
        return result;
    }

    private JSONArray fields(JSONObject... fields)
    {
        JSONArray result = new JSONArray();
        Collections.addAll(result, fields);
        return result;
    }

    private JSONObject field(String key, String label, String type, boolean required, Object defaultValue)
    {
        JSONObject field = new JSONObject();
        field.put("key", key);
        field.put("label", label);
        field.put("type", type);
        field.put("required", required);
        if (defaultValue != null) field.put("defaultValue", defaultValue);
        return field;
    }

    private EmsOpenemsCapability capability(EmsOpenemsEdge edge, String componentId, String key)
    {
        EmsOpenemsCapability row = capabilityMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsCapability>()
                .eq(EmsOpenemsCapability::getTenantId, edge.getTenantId())
                .eq(EmsOpenemsCapability::getEndpointId, edge.getEndpointId())
                .eq(EmsOpenemsCapability::getEdgeId, edge.getEdgeId())
                .eq(EmsOpenemsCapability::getComponentId, componentId)
                .eq(EmsOpenemsCapability::getCapabilityKey, key)
                .eq(EmsOpenemsCapability::getDelFlag, "0")
                .last("limit 1"));
        if (row == null)
        {
            row = new EmsOpenemsCapability();
            row.setTenantId(edge.getTenantId());
            row.setEndpointId(edge.getEndpointId());
            row.setEdgeId(edge.getEdgeId());
            row.setComponentId(componentId);
            row.setCapabilityKey(key);
            row.setDelFlag("0");
        }
        return row;
    }

    private void save(EmsOpenemsCapability row, Date now)
    {
        row.setStatus("ACTIVE");
        row.setLastSeenAt(now);
        row.setUpdateTime(now);
        if (row.getId() == null)
        {
            row.setCreateTime(now);
            try
            {
                capabilityMapper.insert(row);
            }
            catch (DuplicateKeyException ex)
            {
                if (capabilityMapper.updateByUniqueKey(row) == 0)
                {
                    throw ex;
                }
            }
        }
        else capabilityMapper.updateById(row);
    }

    private void markMissing(EmsOpenemsEdge edge, Set<String> seen, Date now)
    {
        List<EmsOpenemsCapability> rows = capabilityMapper.selectList(new LambdaQueryWrapper<EmsOpenemsCapability>()
                .eq(EmsOpenemsCapability::getTenantId, edge.getTenantId())
                .eq(EmsOpenemsCapability::getEndpointId, edge.getEndpointId())
                .eq(EmsOpenemsCapability::getEdgeId, edge.getEdgeId())
                .eq(EmsOpenemsCapability::getDelFlag, "0"));
        for (EmsOpenemsCapability row : rows)
        {
            if (!seen.contains(row.getCapabilityKey()))
            {
                row.setStatus("MISSING");
                row.setUpdateTime(now);
                capabilityMapper.updateById(row);
            }
        }
    }

    @Override
    public Map<String, Object> graph(Long edgeLocalId)
    {
        EmsOpenemsEdge edge = requireEdge(edgeLocalId);
        Long tenantId = edge.getTenantId();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("edge", edge);
        result.put("components", componentMapper.selectList(scope(EmsOpenemsComponent.class, tenantId, edge)));
        result.put("componentRelations", componentRelationMapper.selectList(scope(EmsOpenemsComponentRelation.class, tenantId, edge)));
        result.put("apps", appMapper.selectList(scope(EmsOpenemsAppInstance.class, tenantId, edge)));
        result.put("appComponents", appComponentMapper.selectList(scope(EmsOpenemsAppComponent.class, tenantId, edge)));
        result.put("appRelations", appRelationMapper.selectList(scope(EmsOpenemsAppRelation.class, tenantId, edge)));
        result.put("relationshipSource", "OPENEMS_CONFIG_AND_APP_PROPERTIES");
        return result;
    }

    private <T> LambdaQueryWrapper<T> scope(Class<T> type, Long tenantId, EmsOpenemsEdge edge)
    {
        return new LambdaQueryWrapper<T>().apply("tenant_id = {0}", tenantId)
                .apply("endpoint_id = {0}", edge.getEndpointId()).apply("edge_id = {0}", edge.getEdgeId())
                .apply("del_flag = '0'");
    }

    @Override
    public Map<String, Object> templates(Long edgeLocalId)
    {
        EmsOpenemsEdge edge = requireEdge(edgeLocalId);
        List<EmsOpenemsProtocolTemplate> rows = templateMapper.selectList(new LambdaQueryWrapper<EmsOpenemsProtocolTemplate>()
                .eq(EmsOpenemsProtocolTemplate::getTenantId, edge.getTenantId())
                .eq(EmsOpenemsProtocolTemplate::getEndpointId, edge.getEndpointId())
                .eq(EmsOpenemsProtocolTemplate::getEnabled, "0")
                .eq(EmsOpenemsProtocolTemplate::getDelFlag, "0")
                .orderByAsc(EmsOpenemsProtocolTemplate::getProtocolType, EmsOpenemsProtocolTemplate::getFactoryPid));
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        for (EmsOpenemsProtocolTemplate row : rows)
        {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", row.getId());
            item.put("factoryPid", row.getFactoryPid());
            item.put("appId", row.getAppId());
            item.put("protocolType", row.getProtocolType());
            item.put("driverType", row.getDriverType());
            item.put("communicationType", row.getCommunicationType());
            item.put("schema", JSON.parse(row.getSchemaJson()));
            item.put("defaults", JSON.parse(row.getDefaultJson()));
            item.put("adaptationStatus", row.getAdaptationStatus());
            item.put("advancedJsonAvailable", true);
            item.put("advancedJsonExpanded", false);
            item.put("templateKind", templateKind(row));
            records.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("edgeId", edge.getEdgeId());
        result.put("onlineStatus", edge.getOnlineStatus());
        result.put("records", records);
        result.put("unsupported", records.isEmpty());
        result.put("unsupportedReason", records.isEmpty() ? "目标Edge尚未发现可用App，请在线刷新能力或使用离线基线模板" : null);
        return result;
    }

    private String defaultString(Object value, String fallback)
    {
        return value == null || StringUtils.isEmpty(String.valueOf(value)) ? fallback : String.valueOf(value);
    }

    private String templateKind(EmsOpenemsProtocolTemplate row)
    {
        if (row == null) return "APP";
        if ("SIMULATOR".equals(row.getProtocolType()) || (row.getFactoryPid() != null && row.getFactoryPid().startsWith("Simulator.")))
        {
            return "SIMULATOR";
        }
        return StringUtils.isEmpty(row.getAppId()) ? "FACTORY_COMPAT" : "APP";
    }

    @Override
    public Map<String, Object> componentCapabilities(Long componentLocalId)
    {
        EmsOpenemsComponent component = componentMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsComponent>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsComponent::getTenantId,
                        EmsRequestSupport.currentTenantId())
                .eq(EmsOpenemsComponent::getId, componentLocalId).eq(EmsOpenemsComponent::getDelFlag, "0").last("limit 1"));
        if (component == null) throw new ServiceException("OpenEMS Component不存在或不属于当前租户");
        List<EmsOpenemsCapability> rows = capabilityMapper.selectList(new LambdaQueryWrapper<EmsOpenemsCapability>()
                .eq(EmsOpenemsCapability::getTenantId, component.getTenantId())
                .eq(EmsOpenemsCapability::getEndpointId, component.getEndpointId())
                .eq(EmsOpenemsCapability::getEdgeId, component.getEdgeId())
                .and(w -> w.eq(EmsOpenemsCapability::getComponentId, component.getComponentId())
                        .or().eq(EmsOpenemsCapability::getComponentId, ""))
                .eq(EmsOpenemsCapability::getDelFlag, "0"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("component", component);
        result.put("capabilities", rows);
        return result;
    }

    private EmsOpenemsEdge requireEdge(Long id)
    {
        EmsOpenemsEdge edge = edgeMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsEdge>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsEdge::getTenantId,
                        EmsRequestSupport.currentTenantId())
                .eq(EmsOpenemsEdge::getId, id).eq(EmsOpenemsEdge::getDelFlag, "0").last("limit 1"));
        if (edge == null) throw new ServiceException("OpenEMS Edge不存在或不属于当前租户");
        return edge;
    }

    private String json(Object value)
    {
        return value == null ? null : JSON.toJSONString(value);
    }

    private static class TemplateDefinition
    {
        private String protocolType;
        private String communicationType;
        private JSONObject schema;
        private JSONObject defaults;
        private String status;
    }
}
