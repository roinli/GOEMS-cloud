package com.witos.ems.server.openems;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.domain.entity.EmsOpenemsEndpointSource;
import com.witos.ems.server.domain.entity.EmsServerEndpoint;
import com.witos.ems.server.mapper.EmsOpenemsEndpointSourceMapper;
import com.witos.ems.server.mapper.EmsServerEndpointMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class DefaultOpenemsJsonRpcClient implements OpenemsJsonRpcClient
{
    private static final long HISTORIC_CACHE_TTL_MILLIS = 4 * 60 * 1000L;
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10L);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60L);
    private static final String DEFAULT_LOGIN_USERNAME = "guest";
    private static final String DEFAULT_LOGIN_PASSWORD = "guest";
    private static final int EDGE_PAGE_SIZE = 100;
    private static final int MAX_EDGE_PAGES = 100;

    @Resource
    private EmsServerEndpointMapper serverEndpointMapper;

    @Resource
    private EmsOpenemsEndpointSourceMapper endpointSourceMapper;

    private final Map<Long, WsSession> sessions = new ConcurrentHashMap<Long, WsSession>();
    private final Map<String, HistoricCacheEntry> historicCache = new ConcurrentHashMap<String, HistoricCacheEntry>();

    @Override
    public Map<String, Object> createEdge(Long serverEndpointId, String comment)
    {
        JSONObject params = new JSONObject();
        params.put("comment", comment);
        JSONObject result = call(serverEndpointId, "createEdge", params);
        String edgeId = result.getString("edgeId");
        String apiKey = result.getString("apiKey");
        String setupPassword = result.getString("setupPassword");
        if (StringUtils.isEmpty(edgeId) || StringUtils.isEmpty(apiKey) || StringUtils.isEmpty(setupPassword))
        {
            throw new ServiceException("OpenEMS createEdge响应缺少edgeId、apiKey或setupPassword");
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("edgeId", edgeId);
        values.put("apiKey", apiKey);
        values.put("setupPassword", setupPassword);
        return values;
    }

    @Override
    public List<Map<String, Object>> listEdges(Long serverEndpointId)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int page = 0; page < MAX_EDGE_PAGES; page++)
        {
            JSONObject params = new JSONObject();
            params.put("page", page);
            params.put("limit", EDGE_PAGE_SIZE);
            JSONArray edges = call(serverEndpointId, "getEdges", params).getJSONArray("edges");
            if (edges == null || edges.isEmpty())
            {
                return rows;
            }
            for (Object item : edges)
            {
                if (!(item instanceof JSONObject))
                {
                    throw new ServiceException("OpenEMS getEdges响应包含无效Edge记录");
                }
                JSONObject edge = (JSONObject) item;
                if (StringUtils.isEmpty(edge.getString("id")))
                {
                    throw new ServiceException("OpenEMS getEdges响应缺少Edge ID");
                }
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.putAll(edge);
                rows.add(row);
            }
            if (edges.size() < EDGE_PAGE_SIZE)
            {
                return rows;
            }
        }
        throw new ServiceException("OpenEMS Edge数量超过单次同步上限10000，请拆分Backend端点");
    }

    @Override
    public Map<String, Object> getEdgeConfig(Long serverEndpointId, String edgeId)
    {
        JSONObject result = edgeRpc(serverEndpointId, edgeId, "getEdgeConfig", new JSONObject());
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.putAll(result);
        return values;
    }

    @Override
    public Map<String, Object> getAppSnapshot(Long serverEndpointId, String edgeId)
    {
        JSONObject appsResult;
        try
        {
            appsResult = componentRpc(serverEndpointId, edgeId, "_appManager", "getApps", new JSONObject());
        }
        catch (ServiceException ex)
        {
            if (isMissingAppManager(ex))
            {
                Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
                snapshot.put("apps", new JSONArray());
                snapshot.put("instances", new JSONArray());
                snapshot.put("appManagerAvailable", false);
                snapshot.put("appManagerError", ex.getMessage());
                return snapshot;
            }
            throw ex;
        }
        JSONArray apps = appsResult.getJSONArray("apps");
        JSONArray instances = new JSONArray();
        if (apps != null)
        {
            for (Object item : apps)
            {
                if (!(item instanceof JSONObject))
                {
                    continue;
                }
                JSONObject app = (JSONObject) item;
                JSONArray instanceIds = app.getJSONArray("instanceIds");
                if (instanceIds == null || instanceIds.isEmpty())
                {
                    continue;
                }
                JSONObject instanceParams = new JSONObject();
                instanceParams.put("appId", app.getString("appId"));
                JSONArray appInstances = componentRpc(serverEndpointId, edgeId, "_appManager",
                        "getAppInstances", instanceParams)
                        .getJSONArray("instances");
                if (appInstances == null)
                {
                    throw new ServiceException("OpenEMS getAppInstances响应缺少instances");
                }
                Set<String> expectedIds = new HashSet<String>();
                for (Object instanceId : instanceIds)
                {
                    expectedIds.add(String.valueOf(instanceId));
                }
                Set<String> actualIds = new HashSet<String>();
                for (Object instanceItem : appInstances)
                {
                    if (!(instanceItem instanceof JSONObject))
                    {
                        throw new ServiceException("OpenEMS getAppInstances响应包含无效实例记录");
                    }
                    JSONObject rawInstance = (JSONObject) instanceItem;
                    String instanceId = rawInstance.getString("instanceId");
                    if (StringUtils.isEmpty(instanceId))
                    {
                        throw new ServiceException("OpenEMS getAppInstances响应缺少instanceId");
                    }
                    actualIds.add(instanceId);
                    JSONObject instance = new JSONObject();
                    instance.putAll(rawInstance);
                    JSONObject appSummary = new JSONObject();
                    appSummary.put("appId", app.get("appId"));
                    appSummary.put("name", app.get("name"));
                    appSummary.put("status", app.get("status"));
                    appSummary.put("cardinality", app.get("cardinality"));
                    appSummary.put("permissions", app.get("permissions"));
                    appSummary.put("flags", app.get("flags"));
                    instance.put("appSummary", appSummary);
                    instances.add(instance);
                }
                if (!expectedIds.equals(actualIds))
                {
                    throw new ServiceException("OpenEMS App实例列表在同步期间发生变化，请下次重试");
                }
            }
        }
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("apps", apps == null ? new JSONArray() : apps);
        snapshot.put("instances", instances);
        snapshot.put("appManagerAvailable", true);
        return snapshot;
    }

    @Override
    public Map<String, Object> getCapabilitySnapshot(Long serverEndpointId, String edgeId)
    {
        JSONObject snapshot = new JSONObject();
        snapshot.put("routes", edgeRpc(serverEndpointId, edgeId, "routes", new JSONObject()));
        JSONObject edgeConfig = edgeRpc(serverEndpointId, edgeId, "getEdgeConfig", new JSONObject());
        JSONObject factories = componentRpc(serverEndpointId, edgeId, "_componentManager",
                "getAllComponentFactories", new JSONObject())
                .getJSONObject("factories");
        JSONObject factorySchemas = new JSONObject();
        if (factories != null)
        {
            for (String factoryId : factories.keySet())
            {
                JSONObject params = new JSONObject();
                params.put("factoryId", factoryId);
                JSONObject details = componentRpc(serverEndpointId, edgeId, "_componentManager",
                        "getPropertiesOfFactory", params);
                JSONObject schema = details.getJSONObject("factory");
                if (schema == null)
                {
                    schema = factories.getJSONObject(factoryId);
                }
                if (schema == null)
                {
                    schema = new JSONObject();
                }
                schema.put("properties", details.getJSONArray("properties"));
                factorySchemas.put(factoryId, schema);
            }
        }
        edgeConfig.put("factories", factorySchemas);
        JSONObject components = edgeConfig.getJSONObject("components");
        if (components != null)
        {
            for (Map.Entry<String, Object> entry : components.entrySet())
            {
                JSONObject component = entry.getValue() instanceof JSONObject
                        ? (JSONObject) entry.getValue() : JSON.parseObject(JSON.toJSONString(entry.getValue()));
                JSONObject channelParams = new JSONObject();
                channelParams.put("componentId", entry.getKey());
                channelParams.put("requireEnabled", false);
                JSONArray rows = componentRpc(serverEndpointId, edgeId, "_componentManager",
                        "getChannelsOfComponent", channelParams)
                        .getJSONArray("channels");
                JSONObject channels = new JSONObject();
                if (rows != null)
                {
                    for (Object item : rows)
                    {
                        JSONObject channel = item instanceof JSONObject
                                ? (JSONObject) item : JSON.parseObject(JSON.toJSONString(item));
                        if (!StringUtils.isEmpty(channel.getString("id")))
                        {
                            channels.put(channel.getString("id"), channel);
                        }
                    }
                }
                component.put("channels", channels);
                components.put(entry.getKey(), component);
            }
        }
        snapshot.put("edgeConfig", edgeConfig);
        return new LinkedHashMap<String, Object>(snapshot);
    }

    @Override
    public Map<String, Object> createComponentConfig(Long serverEndpointId, String edgeId, String factoryPid,
                                                     Map<String, Object> properties)
    {
        JSONObject params = new JSONObject();
        params.put("factoryPid", factoryPid);
        params.put("properties", properties(properties));
        return values(componentRpc(serverEndpointId, edgeId, "_componentManager", "createComponentConfig", params));
    }

    @Override
    public Map<String, Object> updateComponentConfig(Long serverEndpointId, String edgeId, String componentId,
                                                     Map<String, Object> properties)
    {
        JSONObject params = new JSONObject();
        params.put("componentId", componentId);
        params.put("properties", properties(properties));
        return values(componentRpc(serverEndpointId, edgeId, "_componentManager", "updateComponentConfig", params));
    }

    @Override
    public Map<String, Object> addAppInstance(Long serverEndpointId, String edgeId, String appId, String key,
                                              String alias, Map<String, Object> properties)
    {
        JSONObject params = new JSONObject();
        params.put("appId", appId);
        if (!StringUtils.isEmpty(key)) params.put("key", key);
        params.put("alias", alias == null ? "" : alias);
        params.put("properties", properties == null ? new JSONObject() : new JSONObject(properties));
        return values(componentRpc(serverEndpointId, edgeId, "_appManager", "addAppInstance", params));
    }

    @Override
    public Map<String, Object> setChannelValue(Long serverEndpointId, String edgeId, String componentId,
                                               String channelId, Object value)
    {
        JSONObject params = new JSONObject();
        params.put("componentId", componentId);
        params.put("channelId", channelId);
        params.put("value", value);
        return values(edgeRpc(serverEndpointId, edgeId, "setChannelValue", params));
    }

    @Override
    public Map<String, Object> componentJsonApi(Long serverEndpointId, String edgeId, String componentId,
                                                String method, Map<String, Object> requestParams)
    {
        JSONObject params = new JSONObject();
        params.put("componentId", componentId);
        params.put("payload", request(method, requestParams == null
                ? new JSONObject() : new JSONObject(requestParams)));
        return values(edgeRpc(serverEndpointId, edgeId, "componentJsonApi", params));
    }

    private JSONArray properties(Map<String, Object> source)
    {
        JSONArray result = new JSONArray();
        if (source == null) return result;
        for (Map.Entry<String, Object> entry : source.entrySet())
        {
            JSONObject property = new JSONObject();
            property.put("name", entry.getKey());
            property.put("value", entry.getValue());
            result.add(property);
        }
        return result;
    }

    private Map<String, Object> values(JSONObject source)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (source != null) result.putAll(source);
        return result;
    }

    @Override
    public List<Map<String, Object>> findComponentsBySerialNo(Long serverEndpointId, String serialNo)
    {
        JSONObject params = new JSONObject();
        params.put("serialNo", serialNo);
        JSONObject result = call(serverEndpointId, "findComponentsBySerialNo", params);
        JSONArray components = result.getJSONArray("components");
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (components == null)
        {
            return rows;
        }
        for (Object item : components)
        {
            if (item instanceof JSONObject)
            {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.putAll((JSONObject) item);
                row.put("serverEndpointId", serverEndpointId);
                rows.add(row);
            }
        }
        return rows;
    }

    @Override
    public Map<String, Object> getEdgeStatus(Long serverEndpointId, String edgeId)
    {
        JSONObject params = new JSONObject();
        JSONArray edgeIds = new JSONArray();
        edgeIds.add(edgeId);
        params.put("edgeIds", edgeIds);
        JSONObject result = call(serverEndpointId, "getEdgesStatus", params);
        JSONObject edgeStatus = result.getJSONObject(edgeId);
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        if (edgeStatus != null)
        {
            values.putAll(edgeStatus);
        }
        return values;
    }

    @Override
    public Map<String, Object> getRealtimeChannelValues(Long serverEndpointId, String edgeId, List<String> channelAddresses)
    {
        JSONObject params = new JSONObject();
        JSONArray ids = new JSONArray();
        ids.add(edgeId);
        params.put("ids", ids);
        params.put("channels", channelAddresses);
        JSONObject result = call(serverEndpointId, "getEdgesChannelsValues", params);
        JSONObject edgeValues = result.getJSONObject(edgeId);
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        if (edgeValues != null)
        {
            values.putAll(edgeValues);
        }
        return values;
    }

    @Override
    public Map<String, Object> queryHistoricTimeseries(Long serverEndpointId, String edgeId, List<String> channelAddresses,
                                                       String fromTime, String toTime)
    {
        JSONObject payloadParams = new JSONObject();
        payloadParams.put("fromDate", datePart(fromTime));
        payloadParams.put("toDate", datePart(toTime));
        payloadParams.put("timezone", "Asia/Shanghai");
        payloadParams.put("channels", channelAddresses);
        JSONObject payload = request("queryHistoricTimeseriesData", payloadParams);

        JSONObject params = new JSONObject();
        params.put("edgeId", edgeId);
        params.put("payload", payload);
        JSONObject payloadResult = historicPayload(serverEndpointId, edgeId, channelAddresses,
            datePart(fromTime), datePart(toTime), params);
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        JSONObject data = payloadResult == null ? null : payloadResult.getJSONObject("data");
        JSONArray timestamps = payloadResult == null ? null : payloadResult.getJSONArray("timestamps");
        if (data != null && timestamps != null)
        {
            ZonedDateTime from = parseTime(fromTime);
            ZonedDateTime to = parseTime(toTime);
            for (String channelAddress : channelAddresses)
            {
                JSONArray sourceValues = data.getJSONArray(channelAddress);
                JSONArray filteredValues = new JSONArray();
                if (sourceValues != null)
                {
                    int size = Math.min(timestamps.size(), sourceValues.size());
                    for (int index = 0; index < size; index++)
                    {
                        ZonedDateTime timestamp = parseTime(timestamps.getString(index));
                        if (!timestamp.isBefore(from) && timestamp.isBefore(to))
                        {
                            filteredValues.add(sourceValues.get(index));
                        }
                    }
                }
                values.put(channelAddress, filteredValues);
            }
        }
        return values;
    }

    public Map<String, Object> testConnection(EmsServerEndpoint endpoint)
    {
        long startedAt = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        try
        {
            WsSession session = session(endpoint);
            JSONObject params = new JSONObject();
            params.put("page", 0);
            params.put("limit", 1);
            JSONObject edgesResult = successfulResult(session.request(request("getEdges", params)));
            JSONArray edges = edgesResult.getJSONArray("edges");
            int edgeCount = edges == null ? 0 : edges.size();
            String sampleEdgeId = null;
            if (edgeCount > 0)
            {
                JSONObject firstEdge = edges.getJSONObject(0);
                sampleEdgeId = firstEdge == null ? null : firstEdge.getString("id");
            }
            if (StringUtils.isNotEmpty(sampleEdgeId))
            {
                JSONObject statusParams = new JSONObject();
                JSONArray edgeIds = new JSONArray();
                edgeIds.add(sampleEdgeId);
                statusParams.put("edgeIds", edgeIds);
                successfulResult(session.request(request("getEdgesStatus", statusParams)));
            }
            result.put("edgeCount", edgeCount);
            result.put("sampleEdgeId", sampleEdgeId);
            result.put("edgeStatusChecked", StringUtils.isNotEmpty(sampleEdgeId));
            return testResult(result, true, "SUCCESS", StringUtils.isEmpty(sampleEdgeId)
                    ? "Backend连接、认证和Edge列表查询成功，当前没有可用于状态检查的Edge"
                    : "Backend连接、认证、Edge列表和状态查询成功", startedAt);
        }
        catch (Exception ex)
        {
            return testResult(result, false, classifyTestError(ex), errorMessage(ex), startedAt);
        }
    }

    private JSONObject successfulResult(JSONObject response)
    {
        if (response == null)
        {
            throw new ServiceException("OpenEMS server无响应");
        }
        if (response.get("error") != null)
        {
            throw new ServiceException("OpenEMS server调用失败：" + response.get("error"));
        }
        JSONObject result = response.getJSONObject("result");
        return result == null ? new JSONObject() : result;
    }

    @PreDestroy
    public void destroy()
    {
        for (WsSession session : sessions.values())
        {
            session.close();
        }
        sessions.clear();
    }

    protected JSONObject call(Long serverEndpointId, String method, JSONObject params)
    {
        WsSession session = session(serverEndpointId);
        JSONObject request = request(method, params);
        JSONObject response = session.request(request);
        if (response == null)
        {
            throw new ServiceException("OpenEMS server无响应");
        }
        if (response.get("error") != null)
        {
            throw new ServiceException("OpenEMS server调用失败：" + response.get("error"));
        }
        JSONObject result = response.getJSONObject("result");
        return result == null ? new JSONObject() : result;
    }

    private JSONObject edgeRpc(Long serverEndpointId, String edgeId, String method, JSONObject payloadParams)
    {
        JSONObject params = new JSONObject();
        params.put("edgeId", edgeId);
        params.put("payload", request(method, payloadParams));
        JSONObject result = call(serverEndpointId, "edgeRpc", params);
        JSONObject payload = result.getJSONObject("payload");
        if (payload == null)
        {
            throw new ServiceException("OpenEMS edgeRpc响应缺少payload：" + method);
        }
        if (payload.get("error") != null)
        {
            throw new ServiceException("OpenEMS Edge调用失败：" + payload.get("error"));
        }
        JSONObject payloadResult = payload.getJSONObject("result");
        return payloadResult == null ? new JSONObject() : payloadResult;
    }

    private JSONObject componentRpc(Long serverEndpointId, String edgeId, String componentId,
                                    String method, JSONObject payloadParams)
    {
        JSONObject params = new JSONObject();
        params.put("componentId", componentId);
        params.put("payload", request(method, payloadParams));
        return edgeRpc(serverEndpointId, edgeId, "componentJsonApi", params);
    }

    private boolean isMissingAppManager(ServiceException ex)
    {
        String message = ex == null || ex.getMessage() == null ? "" : ex.getMessage();
        String lower = message.toLowerCase();
        return lower.contains("getapps") || lower.contains("getappinstances")
                || lower.contains("_appmanager") || lower.contains("appmanager")
                || lower.contains("endpoint with method");
    }

    protected JSONObject request(String method, JSONObject params)
    {
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0");
        request.put("id", UUID.randomUUID().toString());
        request.put("method", method);
        request.put("params", params == null ? new JSONObject() : params);
        return request;
    }

    private WsSession session(Long serverEndpointId)
    {
        EmsServerEndpoint endpoint = serverEndpoint(serverEndpointId);
        return session(endpoint);
    }

    private WsSession session(EmsServerEndpoint endpoint)
    {
        TimeoutSettings timeoutSettings = timeoutSettings(endpoint);
        return sessions.compute(endpoint.getId(), (key, existing) -> {
            if (existing != null && existing.matches(endpoint, timeoutSettings) && existing.isOpen())
            {
                return existing;
            }
            if (existing != null)
            {
                existing.close();
            }
            return new WsSession(endpoint, timeoutSettings);
        });
    }

    private TimeoutSettings timeoutSettings(EmsServerEndpoint endpoint)
    {
        EmsOpenemsEndpointSource source = endpointSourceMapper == null || endpoint == null || endpoint.getId() == null
                ? null : endpointSourceMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsEndpointSource>()
                .eq(EmsOpenemsEndpointSource::getTenantId, endpoint.getTenantId())
                .eq(EmsOpenemsEndpointSource::getEndpointId, endpoint.getId())
                .eq(EmsOpenemsEndpointSource::getSourceType, "API")
                .last("limit 1"));
        Duration connect = source == null || source.getConnectTimeoutSeconds() == null
                ? DEFAULT_CONNECT_TIMEOUT : Duration.ofSeconds(source.getConnectTimeoutSeconds());
        Duration request = source == null || source.getReadTimeoutSeconds() == null
                ? DEFAULT_REQUEST_TIMEOUT : Duration.ofSeconds(source.getReadTimeoutSeconds());
        return new TimeoutSettings(connect, request);
    }

    private EmsServerEndpoint serverEndpoint(Long serverEndpointId)
    {
        EmsServerEndpoint endpoint = serverEndpointMapper.selectById(serverEndpointId);
        if (endpoint == null || !"0".equals(endpoint.getEnabled()) || StringUtils.isEmpty(endpoint.getBaseUrl()))
        {
            throw new ServiceException("OpenEMS server端点未配置或未启用");
        }
        if (!isWebsocketUrl(endpoint.getBaseUrl()))
        {
            throw new ServiceException("OpenEMS server端点必须使用ws://或wss://地址");
        }
        return endpoint;
    }

    private boolean isWebsocketUrl(String url)
    {
        return StringUtils.isNotEmpty(url) && (url.startsWith("ws://") || url.startsWith("wss://"));
    }

    private JSONObject historicPayload(Long serverEndpointId, String edgeId, List<String> channelAddresses,
                                        String fromDate, String toDate, JSONObject params)
    {
        String cacheKey = serverEndpointId + "|" + edgeId + "|" + fromDate + "|" + toDate + "|"
                + String.join(",", channelAddresses);
        long now = System.currentTimeMillis();
        HistoricCacheEntry cached = historicCache.get(cacheKey);
        if (cached != null && now - cached.loadedAt < HISTORIC_CACHE_TTL_MILLIS)
        {
            return cached.payloadResult;
        }
        JSONObject result = call(serverEndpointId, "edgeRpc", params);
        JSONObject payloadResponse = result.getJSONObject("payload");
        JSONObject payloadResult = payloadResponse == null || payloadResponse.get("error") != null
                ? (payloadResponse == null ? result : null)
                : payloadResponse.getJSONObject("result");
        if (payloadResult == null)
        {
            return new JSONObject();
        }
        if (historicCache.size() >= 256)
        {
            historicCache.clear();
        }
        historicCache.put(cacheKey, new HistoricCacheEntry(now, payloadResult));
        return payloadResult;
    }

    private Map<String, Object> testResult(Map<String, Object> result, boolean success, String category,
                                           String message, long startedAt)
    {
        result.put("success", success);
        result.put("category", category);
        result.put("message", message);
        result.put("latencyMs", Math.max(0L, System.currentTimeMillis() - startedAt));
        return result;
    }

    private String classifyTestError(Exception ex)
    {
        String message = errorMessage(ex).toLowerCase();
        if (message.contains("timeout") || message.contains("timed out") || message.contains("超时"))
        {
            return "TIMEOUT";
        }
        if (message.contains("auth") || message.contains("credential") || message.contains("token") || message.contains("password"))
        {
            return "AUTH";
        }
        if (message.contains("json") || message.contains("protocol") || message.contains("协议"))
        {
            return "PROTOCOL";
        }
        if (message.contains("capability"))
        {
            return "CAPABILITY";
        }
        return "NETWORK";
    }

    private String errorMessage(Exception ex)
    {
        return ex.getMessage() == null ? "端点连接测试失败" : ex.getMessage();
    }

    private String datePart(String value)
    {
        if (StringUtils.isEmpty(value) || value.length() < 10)
        {
            return value;
        }
        return value.substring(0, 10);
    }

    private ZonedDateTime parseTime(String value)
    {
        if (StringUtils.isEmpty(value))
        {
            throw new ServiceException("OpenEMS历史查询时间不能为空");
        }
        try
        {
            return ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME);
        }
        catch (DateTimeParseException ignored)
        {
            try
            {
                return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(ZoneId.of("Asia/Shanghai"));
            }
            catch (DateTimeParseException ignoredToo)
            {
                return OffsetDateTime.parse(value).toZonedDateTime();
            }
        }
    }

    private static class HistoricCacheEntry
    {
        private final long loadedAt;
        private final JSONObject payloadResult;

        private HistoricCacheEntry(long loadedAt, JSONObject payloadResult)
        {
            this.loadedAt = loadedAt;
            this.payloadResult = payloadResult;
        }
    }

    private static class TimeoutSettings
    {
        private final Duration connectTimeout;
        private final Duration requestTimeout;

        private TimeoutSettings(Duration connectTimeout, Duration requestTimeout)
        {
            this.connectTimeout = connectTimeout;
            this.requestTimeout = requestTimeout;
        }
    }

    private static class WsSession
    {
        private static final int NORMAL_CLOSURE = 1000;

        private final EmsServerEndpoint endpoint;
        private final TimeoutSettings timeoutSettings;
        private final Object lock = new Object();
        private final PendingRequests pendingRequests = new PendingRequests();
        private volatile WebSocketClient webSocket;

        private WsSession(EmsServerEndpoint endpoint, TimeoutSettings timeoutSettings)
        {
            this.endpoint = endpoint;
            this.timeoutSettings = timeoutSettings;
        }

        private boolean matches(EmsServerEndpoint other, TimeoutSettings otherTimeoutSettings)
        {
            return other != null && Objects.equals(endpoint.getId(), other.getId())
                    && Objects.equals(endpoint.getBaseUrl(), other.getBaseUrl())
                    && Objects.equals(endpoint.getAuthType(), other.getAuthType())
                    && Objects.equals(endpoint.getCredentialRef(), other.getCredentialRef())
                    && Objects.equals(timeoutSettings.connectTimeout, otherTimeoutSettings.connectTimeout)
                    && Objects.equals(timeoutSettings.requestTimeout, otherTimeoutSettings.requestTimeout);
        }

        private boolean isOpen()
        {
            return webSocket != null && webSocket.isOpen();
        }

        private JSONObject request(JSONObject request)
        {
            synchronized (lock)
            {
                ensureOpen();
                CompletableFuture<JSONObject> future = new CompletableFuture<JSONObject>();
                pendingRequests.register(String.valueOf(request.get("id")), future);
                try
                {
                    webSocket.send(request.toJSONString());
                }
                catch (Exception ex)
                {
                    pendingRequests.remove(String.valueOf(request.get("id")));
                    close();
                    throw new ServiceException("OpenEMS WebSocket请求发送失败：" + ex.getMessage());
                }
                try
                {
                    return future.get(timeoutSettings.requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
                }
                catch (Exception ex)
                {
                    close();
                    if (ex instanceof TimeoutException)
                    {
                        throw new ServiceException("OpenEMS WebSocket请求超时");
                    }
                    throw new ServiceException("OpenEMS WebSocket请求失败：" + ex.getMessage());
                }
            }
        }

        private void ensureOpen()
        {
            if (webSocket != null && webSocket.isOpen())
            {
                return;
            }
            try
            {
                WebSocketClient client = new OpenemsWebSocketClient(URI.create(endpoint.getBaseUrl()));
                boolean connected = client.connectBlocking(timeoutSettings.connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!connected)
                {
                    throw new ServiceException("连接超时");
                }
                webSocket = client;
                authenticate();
            }
            catch (Exception ex)
            {
                close();
                throw new ServiceException("OpenEMS WebSocket连接失败：" + ex.getMessage());
            }
        }

        private void authenticate()
        {
            JSONObject params = new JSONObject();
            String authType = normalizeAuthType(endpoint.getAuthType());
            if ("NONE".equals(authType))
            {
                authType = "BASIC";
            }
            if ("PASSWORD".equals(authType) || "BASIC".equals(authType))
            {
                String credential = resolveCredential(endpoint.getCredentialRef());
                String[] usernamePassword = splitUsernamePassword(credential);
                params.put("username", usernamePassword[0]);
                params.put("password", usernamePassword[1]);
                sendAuth("authenticateWithPassword", params);
                return;
            }
            if ("BEARER".equals(authType) || "OAUTH2".equals(authType) || "API_KEY".equals(authType))
            {
                String token = resolveCredential(endpoint.getCredentialRef());
                params.put("token", token);
                sendAuth("authenticateWithToken", params);
                return;
            }
            throw new ServiceException("OpenEMS server端点认证方式不支持：" + authType);
        }

        private void sendAuth(String method, JSONObject params)
        {
            JSONObject request = new JSONObject();
            request.put("jsonrpc", "2.0");
            request.put("id", UUID.randomUUID().toString());
            request.put("method", method);
            request.put("params", params);
            CompletableFuture<JSONObject> future = new CompletableFuture<JSONObject>();
            String requestId = String.valueOf(request.get("id"));
            pendingRequests.register(requestId, future);
            try
            {
                webSocket.send(request.toJSONString());
            }
            catch (Exception ex)
            {
                pendingRequests.remove(requestId);
                close();
                throw new ServiceException("OpenEMS认证请求发送失败：" + ex.getMessage());
            }
            try
            {
                JSONObject response = future.get(timeoutSettings.requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (response != null && response.get("error") != null)
                {
                    throw new ServiceException("OpenEMS认证失败：" + response.get("error"));
                }
            }
            catch (Exception ex)
            {
                close();
                throw new ServiceException("OpenEMS认证失败：" + ex.getMessage());
            }
        }

        private String normalizeAuthType(String authType)
        {
            return StringUtils.isEmpty(authType) ? "NONE" : authType.trim().toUpperCase();
        }

        private String resolveCredential(String credentialRef)
        {
            if (StringUtils.isEmpty(credentialRef))
            {
                return "";
            }
            String value = credentialRef.trim();
            if (value.startsWith("env:"))
            {
                return StringUtils.defaultString(System.getenv(value.substring(4)), "");
            }
            if (value.startsWith("sys:"))
            {
                return StringUtils.defaultString(System.getProperty(value.substring(4)), "");
            }
            return value;
        }

        private String[] splitUsernamePassword(String credential)
        {
            if (StringUtils.isEmpty(credential))
            {
                return new String[] {DEFAULT_LOGIN_USERNAME, DEFAULT_LOGIN_PASSWORD};
            }
            int index = credential.indexOf(':');
            if (index > 0)
            {
                return new String[] {credential.substring(0, index), credential.substring(index + 1)};
            }
            return new String[] {DEFAULT_LOGIN_USERNAME, credential};
        }

        private void close()
        {
            WebSocketClient current = webSocket;
            webSocket = null;
            pendingRequests.failAll(new ServiceException("OpenEMS WebSocket连接已关闭"));
            if (current != null)
            {
                try
                {
                    current.close(NORMAL_CLOSURE, "close");
                }
                catch (Exception ignored)
                {
                }
            }
        }

        private class OpenemsWebSocketClient extends WebSocketClient
        {
            private OpenemsWebSocketClient(URI serverUri)
            {
                super(serverUri);
                setConnectionLostTimeout((int) timeoutSettings.requestTimeout.getSeconds());
            }

            @Override
            public void onOpen(ServerHandshake handshakedata)
            {
            }

            @Override
            public void onMessage(String message)
            {
                pendingRequests.complete(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote)
            {
                pendingRequests.failAll(new ServiceException("OpenEMS WebSocket连接关闭：" + reason));
            }

            @Override
            public void onError(Exception ex)
            {
                pendingRequests.failAll(ex);
            }
        }

        private class PendingRequests
        {
            private final Map<String, CompletableFuture<JSONObject>> futures = new ConcurrentHashMap<String, CompletableFuture<JSONObject>>();

            private void register(String id, CompletableFuture<JSONObject> future)
            {
                futures.put(id, future);
            }

            private void remove(String id)
            {
                futures.remove(id);
            }

            private void failAll(Exception error)
            {
                for (CompletableFuture<JSONObject> future : futures.values())
                {
                    future.completeExceptionally(error);
                }
                futures.clear();
            }

            private void complete(String raw)
            {
                try
                {
                    JSONObject response = JSON.parseObject(raw);
                    String id = String.valueOf(response.get("id"));
                    CompletableFuture<JSONObject> future = futures.remove(id);
                    if (future != null)
                    {
                        future.complete(response);
                    }
                }
                catch (Exception ex)
                {
                    failAll(ex);
                }
            }
        }
    }

}
