package com.witos.ems.server.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.domain.entity.EmsOpenemsEndpointSource;
import com.witos.ems.server.domain.entity.EmsServerEndpoint;
import com.witos.ems.server.mapper.EmsOpenemsEndpointSourceMapper;
import com.witos.ems.server.mapper.EmsServerEndpointMapper;
import com.witos.ems.server.openems.DefaultOpenemsJsonRpcClient;
import com.witos.ems.server.openems.OpenemsInfluxConnectionTester;
import com.witos.ems.server.service.EmsServerEndpointService;
import com.witos.ems.server.support.EmsPageSupport;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class EmsServerEndpointServiceImpl implements EmsServerEndpointService
{
    private static final String API = "API";
    private static final String RAW_INFLUX = "RAW_INFLUX";
    private static final String AGGREGATED_INFLUX = "AGGREGATED_INFLUX";
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    private static final Set<String> AUTH_TYPES = new HashSet<String>(Arrays.asList("NONE", "API_KEY", "BEARER", "OAUTH2", "BASIC"));
    private static final Set<String> INFLUX_VERSIONS = new HashSet<String>(Arrays.asList("INFLUX_1", "INFLUX_2"));
    private static final Pattern ENDPOINT_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{1,63}$");
    private static final Pattern CREDENTIAL_REFERENCE = Pattern.compile("^(env|sys):[A-Za-z_][A-Za-z0-9_.-]*$");
    private static final int MAX_CREDENTIAL_LENGTH = 4096;

    @Resource
    private EmsServerEndpointMapper endpointMapper;

    @Resource
    private EmsOpenemsEndpointSourceMapper sourceMapper;

    @Resource
    private DefaultOpenemsJsonRpcClient openemsClient;

    @Resource
    private OpenemsInfluxConnectionTester influxConnectionTester;

    @Override
    public IPage<Map<String, Object>> list(Map<String, String> query)
    {
        Long tenantId = EmsRequestSupport.currentTenantId();
        LambdaQueryWrapper<EmsServerEndpoint> wrapper = new LambdaQueryWrapper<EmsServerEndpoint>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsServerEndpoint::getTenantId, tenantId)
                .eq(EmsServerEndpoint::getScopeType, "TENANT")
                .like(StringUtils.isNotEmpty(query.get("endpointCode")), EmsServerEndpoint::getEndpointCode, query.get("endpointCode"))
                .like(StringUtils.isNotEmpty(query.get("endpointName")), EmsServerEndpoint::getEndpointName, query.get("endpointName"))
                .eq(StringUtils.isNotEmpty(query.get("enabled")), EmsServerEndpoint::getEnabled, query.get("enabled"))
                .orderByDesc(EmsServerEndpoint::getId);
        IPage<EmsServerEndpoint> source = endpointMapper.selectPage(EmsPageSupport.<EmsServerEndpoint>page(), wrapper);
        Page<Map<String, Object>> result = new Page<Map<String, Object>>(source.getCurrent(), source.getSize(), source.getTotal());
        Map<Long, List<EmsOpenemsEndpointSource>> sources = sourcesByEndpoint(source.getRecords());
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (EmsServerEndpoint endpoint : source.getRecords())
        {
            rows.add(toView(endpoint, sources.get(endpoint.getId())));
        }
        result.setRecords(rows);
        return result;
    }

    @Override
    public List<Map<String, Object>> listEnabledOptions()
    {
        List<EmsServerEndpoint> endpoints = endpointMapper.selectList(new LambdaQueryWrapper<EmsServerEndpoint>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsServerEndpoint::getTenantId,
                        EmsRequestSupport.currentTenantId())
                .eq(EmsServerEndpoint::getScopeType, "TENANT")
                .eq(EmsServerEndpoint::getEnabled, "0")
                .orderByAsc(EmsServerEndpoint::getEndpointName, EmsServerEndpoint::getId));
        List<Map<String, Object>> options = new ArrayList<Map<String, Object>>();
        for (EmsServerEndpoint endpoint : endpoints)
        {
            Map<String, Object> option = new LinkedHashMap<String, Object>();
            option.put("id", endpoint.getId());
            option.put("endpointCode", endpoint.getEndpointCode());
            option.put("endpointName", endpoint.getEndpointName());
            options.add(option);
        }
        return options;
    }

    @Override
    public Map<String, Object> get(Long id)
    {
        EmsServerEndpoint endpoint = requireEndpoint(id);
        return toView(endpoint, listSources(endpoint.getId(), endpoint.getTenantId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Map<String, Object> body)
    {
        if (body == null)
        {
            throw new ServiceException("端点参数不能为空");
        }
        Long id = EmsRequestSupport.coalesceId(body, "id");
        EmsServerEndpoint existing = id == null ? null : requireEndpoint(id);
        Long tenantId = existing == null ? EmsRequestSupport.requestedTenantId(body) : existing.getTenantId();
        Map<String, EmsOpenemsEndpointSource> existingSources = sourceByType(
                existing == null ? new ArrayList<EmsOpenemsEndpointSource>() : listSources(existing.getId(), tenantId));

        String endpointCode = normalize(body.get("endpointCode"));
        String endpointName = normalize(body.get("endpointName"));
        String baseUrl = firstNotEmpty(body.get("wsUrl"), body.get("baseUrl"));
        String authType = normalize(body.get("authType")).toUpperCase(Locale.ROOT);
        String credentialRef = normalize(body.get("credentialRef"));
        String enabled = normalize(body.get("enabled"));
        String defaultTimezone = normalize(body.get("defaultTimezone"));
        EmsOpenemsEndpointSource existingApi = existingSources.get(API);

        if (!ENDPOINT_CODE.matcher(endpointCode).matches())
        {
            throw new ServiceException("端点编码需为2-64位字母、数字、点、下划线或中划线");
        }
        if (StringUtils.isEmpty(endpointName) || endpointName.length() > 128)
        {
            throw new ServiceException("端点名称不能为空且不能超过128个字符");
        }
        validateUrl(baseUrl, "ws", "wss", "Backend地址仅支持有效的WebSocket URL");
        if (!AUTH_TYPES.contains(authType))
        {
            throw new ServiceException("认证类型不合法");
        }
        if (StringUtils.isEmpty(enabled))
        {
            enabled = existing == null ? "0" : existing.getEnabled();
        }
        validateEnabled(enabled);
        if ("NONE".equals(authType))
        {
            credentialRef = "";
        }
        else if (StringUtils.isEmpty(credentialRef) && existing != null)
        {
            credentialRef = existing.getCredentialRef();
        }
        validateCredential(authType, credentialRef);
        if (StringUtils.isEmpty(defaultTimezone))
        {
            defaultTimezone = existingApi == null || StringUtils.isEmpty(existingApi.getTimezone())
                    ? DEFAULT_TIMEZONE : existingApi.getTimezone();
        }
        validateTimezone(defaultTimezone);

        Long duplicateCount = endpointMapper.selectCount(new LambdaQueryWrapper<EmsServerEndpoint>()
                .eq(EmsServerEndpoint::getTenantId, tenantId)
                .eq(EmsServerEndpoint::getEndpointCode, endpointCode)
                .ne(id != null, EmsServerEndpoint::getId, id));
        if (duplicateCount != null && duplicateCount > 0)
        {
            throw new ServiceException("端点编码已存在");
        }

        EmsServerEndpoint endpoint = existing == null ? new EmsServerEndpoint() : existing;
        endpoint.setTenantId(tenantId);
        endpoint.setScopeType("TENANT");
        endpoint.setEndpointCode(endpointCode);
        endpoint.setEndpointName(endpointName);
        endpoint.setBaseUrl(baseUrl);
        endpoint.setAuthType(authType);
        endpoint.setCredentialRef(credentialRef);
        endpoint.setEnabled(enabled);
        if (existing == null)
        {
            endpoint.setDelFlag("0");
            endpointMapper.insert(endpoint);
        }
        else
        {
            endpointMapper.updateById(endpoint);
        }

        EmsOpenemsEndpointSource api = saveApiSource(endpoint, existingApi, defaultTimezone, body);
        EmsOpenemsEndpointSource raw = saveInfluxSource(endpoint, existingSources.get(RAW_INFLUX), RAW_INFLUX,
                mapValue(body.get("rawInflux")), true, defaultTimezone);
        EmsOpenemsEndpointSource aggregated = saveInfluxSource(endpoint, existingSources.get(AGGREGATED_INFLUX),
                AGGREGATED_INFLUX, mapValue(body.get("aggregatedInflux")), false, defaultTimezone);

        List<EmsOpenemsEndpointSource> savedSources = new ArrayList<EmsOpenemsEndpointSource>();
        savedSources.add(api);
        savedSources.add(raw);
        if (aggregated != null)
        {
            savedSources.add(aggregated);
        }
        return toView(endpoint, savedSources);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> changeStatus(Long id, String enabled)
    {
        EmsServerEndpoint endpoint = requireEndpoint(id);
        validateEnabled(enabled);
        endpoint.setEnabled(enabled);
        endpointMapper.updateById(endpoint);
        EmsOpenemsEndpointSource api = findSource(endpoint.getId(), API, endpoint.getTenantId());
        if (api != null)
        {
            api.setEnabled(enabled);
            sourceMapper.updateById(api);
        }
        return toView(endpoint, listSources(endpoint.getId(), endpoint.getTenantId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean remove(Long id)
    {
        EmsServerEndpoint endpoint = requireEndpoint(id);
        sourceMapper.delete(new LambdaQueryWrapper<EmsOpenemsEndpointSource>()
                .eq(EmsOpenemsEndpointSource::getTenantId, endpoint.getTenantId())
                .eq(EmsOpenemsEndpointSource::getEndpointId, endpoint.getId()));
        return endpointMapper.deleteById(id) > 0;
    }

    @Override
    public Map<String, Object> test(Long id)
    {
        EmsServerEndpoint endpoint = requireEndpoint(id);
        Map<String, EmsOpenemsEndpointSource> sources = sourceByType(listSources(endpoint.getId(), endpoint.getTenantId()));
        Map<String, Object> apiResult = normalizeApiTest(openemsClient.testConnection(endpoint));
        Map<String, Object> rawResult = influxConnectionTester.test(sources.get(RAW_INFLUX));
        Map<String, Object> aggregatedResult = influxConnectionTester.test(sources.get(AGGREGATED_INFLUX));
        persistTestResult(sources.get(API), apiResult);
        persistTestResult(sources.get(RAW_INFLUX), rawResult);
        persistTestResult(sources.get(AGGREGATED_INFLUX), aggregatedResult);

        boolean apiSuccess = Boolean.TRUE.equals(apiResult.get("success"));
        boolean rawSuccess = Boolean.TRUE.equals(rawResult.get("success"));
        boolean aggregatedAcceptable = Boolean.TRUE.equals(aggregatedResult.get("success"))
                || "NOT_CONFIGURED".equals(aggregatedResult.get("status"));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("overallStatus", apiSuccess && rawSuccess && aggregatedAcceptable ? "SUCCESS" : "FAILED");
        result.put("api", apiResult);
        result.put("rawInflux", rawResult);
        result.put("aggregatedInflux", aggregatedResult);
        return result;
    }

    private EmsOpenemsEndpointSource saveApiSource(EmsServerEndpoint endpoint, EmsOpenemsEndpointSource source,
                                                    String timezone, Map<String, Object> body)
    {
        EmsOpenemsEndpointSource api = source == null ? newSource(endpoint, API) : source;
        api.setUrl(endpoint.getBaseUrl());
        api.setVersion("OPENEMS_BACKEND");
        api.setQueryLanguage("JSON_RPC");
        api.setTimezone(timezone);
        api.setCredentialRef(endpoint.getCredentialRef());
        api.setConnectTimeoutSeconds(intValue(body.get("apiConnectTimeoutSeconds"),
                api.getConnectTimeoutSeconds(), 10, 1, 60, "Backend连接超时需为1-60秒"));
        api.setReadTimeoutSeconds(intValue(body.get("apiTimeoutSeconds"),
                api.getReadTimeoutSeconds(), 60, 1, 300, "Backend读取超时需为1-300秒"));
        api.setEnabled(endpoint.getEnabled());
        upsertSource(api);
        return api;
    }

    private EmsOpenemsEndpointSource saveInfluxSource(EmsServerEndpoint endpoint, EmsOpenemsEndpointSource source,
                                                       String sourceType, Map<String, Object> body, boolean required,
                                                       String defaultTimezone)
    {
        if (body == null)
        {
            if (source != null)
            {
                if (required && !"0".equals(source.getEnabled()))
                {
                    throw new ServiceException("Raw Influx必须配置并启用");
                }
                return source;
            }
            if (required)
            {
                throw new ServiceException("Raw Influx配置不能为空");
            }
            return null;
        }
        EmsOpenemsEndpointSource value = source == null ? newSource(endpoint, sourceType) : source;
        String version = normalize(body.get("version")).toUpperCase(Locale.ROOT);
        String url = normalize(body.get("url"));
        String credentialRef = normalize(body.get("credentialRef"));
        String timezone = normalize(body.get("timezone"));
        String measurement = normalize(body.get("measurement"));
        String edgeTag = normalize(body.get("edgeTag"));
        String enabled = normalize(body.get("enabled"));
        if (!INFLUX_VERSIONS.contains(version))
        {
            throw new ServiceException(sourceType + "版本仅支持INFLUX_1或INFLUX_2");
        }
        validateUrl(url, "http", "https", sourceType + "地址仅支持有效的HTTP URL");
        if (StringUtils.isEmpty(credentialRef) && source != null)
        {
            credentialRef = source.getCredentialRef();
        }
        validateOptionalCredential(credentialRef);
        if (StringUtils.isEmpty(timezone))
        {
            timezone = StringUtils.isEmpty(value.getTimezone()) ? defaultTimezone : value.getTimezone();
        }
        validateTimezone(timezone);
        if (StringUtils.isEmpty(measurement))
        {
            measurement = "data";
        }
        if (StringUtils.isEmpty(edgeTag))
        {
            edgeTag = "edge";
        }
        if (measurement.length() > 128 || edgeTag.length() > 64)
        {
            throw new ServiceException("measurement不能超过128字符，Edge标签不能超过64字符");
        }
        if (StringUtils.isEmpty(enabled))
        {
            enabled = source == null ? "0" : source.getEnabled();
        }
        validateEnabled(enabled);
        if (required && !"0".equals(enabled))
        {
            throw new ServiceException("Raw Influx必须配置并启用");
        }

        value.setUrl(url);
        value.setVersion(version);
        value.setQueryLanguage("INFLUX_1".equals(version) ? "INFLUXQL" : "FLUX");
        value.setOrg(normalize(body.get("org")));
        value.setBucket(normalize(body.get("bucket")));
        value.setDatabaseName(normalize(body.get("databaseName")));
        value.setRetentionPolicy(normalize(body.get("retentionPolicy")));
        value.setMeasurement(measurement);
        value.setEdgeTag(edgeTag);
        value.setTimezone(timezone);
        value.setCredentialRef(credentialRef);
        if (body.containsKey("queryConfig"))
        {
            String queryConfigJson = body.get("queryConfig") == null ? null : JSON.toJSONString(body.get("queryConfig"));
            if (queryConfigJson != null && queryConfigJson.length() > 65535)
            {
                throw new ServiceException("聚合查询配置不能超过65535个字符");
            }
            value.setQueryConfigJson(queryConfigJson);
        }
        value.setConnectTimeoutSeconds(intValue(body.get("connectTimeoutSeconds"), value.getConnectTimeoutSeconds(),
                10, 1, 60, sourceType + "连接超时需为1-60秒"));
        value.setReadTimeoutSeconds(intValue(body.get("readTimeoutSeconds"), value.getReadTimeoutSeconds(),
                60, 1, 300, sourceType + "读取超时需为1-300秒"));
        value.setEnabled(enabled);
        validateInfluxFields(value);
        upsertSource(value);
        return value;
    }

    private void validateInfluxFields(EmsOpenemsEndpointSource source)
    {
        if ("INFLUX_1".equals(source.getVersion()))
        {
            String database = StringUtils.isNotEmpty(source.getDatabaseName()) ? source.getDatabaseName() : source.getBucket();
            if (StringUtils.isEmpty(database))
            {
                throw new ServiceException(source.getSourceType() + "的InfluxDB 1.x必须配置databaseName或bucket");
            }
            source.setDatabaseName(database);
            source.setBucket(StringUtils.isEmpty(source.getBucket()) ? database : source.getBucket());
            source.setOrg(StringUtils.isEmpty(source.getOrg()) ? "-" : source.getOrg());
            return;
        }
        if (StringUtils.isEmpty(source.getOrg()) || StringUtils.isEmpty(source.getBucket()))
        {
            throw new ServiceException(source.getSourceType() + "的InfluxDB 2.x必须配置org和bucket");
        }
        source.setDatabaseName("");
        source.setRetentionPolicy("");
    }

    private Map<String, Object> normalizeApiTest(Map<String, Object> original)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (original != null)
        {
            result.putAll(original);
        }
        boolean success = Boolean.TRUE.equals(result.get("success"));
        result.put("sourceType", API);
        result.put("status", success ? "SUCCESS" : "FAILED");
        result.put("errorCode", success ? null : result.get("category"));
        return result;
    }

    private void persistTestResult(EmsOpenemsEndpointSource source, Map<String, Object> result)
    {
        if (source == null || result == null)
        {
            return;
        }
        source.setLastTestStatus(String.valueOf(result.get("status")));
        source.setLastTestAt(new Date());
        source.setLastErrorCode(result.get("errorCode") == null ? null : String.valueOf(result.get("errorCode")));
        source.setLastErrorMessage(Boolean.TRUE.equals(result.get("success")) ? null : truncate(String.valueOf(result.get("message")), 1024));
        sourceMapper.updateById(source);
    }

    private EmsServerEndpoint requireEndpoint(Long id)
    {
        if (id == null)
        {
            throw new ServiceException("端点不能为空");
        }
        EmsServerEndpoint endpoint = endpointMapper.selectById(id);
        if (endpoint == null || (!EmsRequestSupport.isPlatformAdmin()
                && !Objects.equals(endpoint.getTenantId(), EmsRequestSupport.currentTenantId()))
                || !"TENANT".equals(endpoint.getScopeType()))
        {
            throw new ServiceException("端点不存在或无权访问");
        }
        return endpoint;
    }

    private EmsOpenemsEndpointSource findSource(Long endpointId, String sourceType, Long tenantId)
    {
        return sourceMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsEndpointSource>()
                .eq(EmsOpenemsEndpointSource::getTenantId, tenantId)
                .eq(EmsOpenemsEndpointSource::getEndpointId, endpointId)
                .eq(EmsOpenemsEndpointSource::getSourceType, sourceType)
                .last("limit 1"));
    }

    private List<EmsOpenemsEndpointSource> listSources(Long endpointId, Long tenantId)
    {
        return sourceMapper.selectList(new LambdaQueryWrapper<EmsOpenemsEndpointSource>()
                .eq(EmsOpenemsEndpointSource::getTenantId, tenantId)
                .eq(EmsOpenemsEndpointSource::getEndpointId, endpointId));
    }

    private Map<Long, List<EmsOpenemsEndpointSource>> sourcesByEndpoint(List<EmsServerEndpoint> endpoints)
    {
        Map<Long, List<EmsOpenemsEndpointSource>> result = new HashMap<Long, List<EmsOpenemsEndpointSource>>();
        if (endpoints == null || endpoints.isEmpty())
        {
            return result;
        }
        List<Long> ids = new ArrayList<Long>();
        for (EmsServerEndpoint endpoint : endpoints)
        {
            ids.add(endpoint.getId());
        }
        List<EmsOpenemsEndpointSource> sources = sourceMapper.selectList(new LambdaQueryWrapper<EmsOpenemsEndpointSource>()
                .in(EmsOpenemsEndpointSource::getEndpointId, ids));
        for (EmsOpenemsEndpointSource source : sources)
        {
            result.computeIfAbsent(source.getEndpointId(), ignored -> new ArrayList<EmsOpenemsEndpointSource>()).add(source);
        }
        return result;
    }

    private Map<String, EmsOpenemsEndpointSource> sourceByType(List<EmsOpenemsEndpointSource> sources)
    {
        Map<String, EmsOpenemsEndpointSource> result = new HashMap<String, EmsOpenemsEndpointSource>();
        if (sources != null)
        {
            for (EmsOpenemsEndpointSource source : sources)
            {
                result.put(source.getSourceType(), source);
            }
        }
        return result;
    }

    private EmsOpenemsEndpointSource newSource(EmsServerEndpoint endpoint, String sourceType)
    {
        EmsOpenemsEndpointSource source = new EmsOpenemsEndpointSource();
        source.setTenantId(endpoint.getTenantId());
        source.setEndpointId(endpoint.getId());
        source.setSourceType(sourceType);
        source.setDelFlag("0");
        return source;
    }

    private void upsertSource(EmsOpenemsEndpointSource source)
    {
        if (source.getId() == null)
        {
            sourceMapper.insert(source);
        }
        else
        {
            sourceMapper.updateById(source);
        }
    }

    private Map<String, Object> toView(EmsServerEndpoint endpoint, List<EmsOpenemsEndpointSource> sources)
    {
        Map<String, EmsOpenemsEndpointSource> byType = sourceByType(sources);
        EmsOpenemsEndpointSource api = byType.get(API);
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", endpoint.getId());
        row.put("scopeType", "TENANT");
        row.put("endpointCode", endpoint.getEndpointCode());
        row.put("endpointName", endpoint.getEndpointName());
        row.put("baseUrl", endpoint.getBaseUrl());
        row.put("wsUrl", endpoint.getBaseUrl());
        row.put("authType", endpoint.getAuthType());
        row.put("credentialConfigured", StringUtils.isNotEmpty(endpoint.getCredentialRef()));
        row.put("defaultTimezone", api == null || StringUtils.isEmpty(api.getTimezone()) ? DEFAULT_TIMEZONE : api.getTimezone());
        row.put("apiConnectTimeoutSeconds", api == null ? 10 : api.getConnectTimeoutSeconds());
        row.put("apiTimeoutSeconds", api == null ? 60 : api.getReadTimeoutSeconds());
        row.put("api", sourceView(api));
        row.put("rawInflux", sourceView(byType.get(RAW_INFLUX)));
        row.put("aggregatedInflux", sourceView(byType.get(AGGREGATED_INFLUX)));
        row.put("enabled", endpoint.getEnabled());
        row.put("createTime", endpoint.getCreateTime());
        row.put("updateTime", endpoint.getUpdateTime());
        return row;
    }

    private Map<String, Object> sourceView(EmsOpenemsEndpointSource source)
    {
        if (source == null)
        {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("sourceType", source.getSourceType());
        row.put("url", source.getUrl());
        row.put("version", source.getVersion());
        row.put("queryLanguage", source.getQueryLanguage());
        row.put("org", source.getOrg());
        row.put("bucket", source.getBucket());
        row.put("databaseName", source.getDatabaseName());
        row.put("retentionPolicy", source.getRetentionPolicy());
        row.put("measurement", source.getMeasurement());
        row.put("edgeTag", source.getEdgeTag());
        row.put("timezone", source.getTimezone());
        row.put("credentialConfigured", StringUtils.isNotEmpty(source.getCredentialRef()));
        row.put("queryConfig", StringUtils.isEmpty(source.getQueryConfigJson())
                ? null : JSON.parseObject(source.getQueryConfigJson()));
        row.put("connectTimeoutSeconds", source.getConnectTimeoutSeconds());
        row.put("readTimeoutSeconds", source.getReadTimeoutSeconds());
        row.put("enabled", source.getEnabled());
        row.put("lastTestStatus", source.getLastTestStatus());
        row.put("lastTestAt", source.getLastTestAt());
        row.put("lastErrorCode", source.getLastErrorCode());
        row.put("lastErrorMessage", source.getLastErrorMessage());
        return row;
    }

    private void validateUrl(String value, String firstScheme, String secondScheme, String message)
    {
        try
        {
            URI uri = new URI(value);
            boolean schemeValid = firstScheme.equalsIgnoreCase(uri.getScheme()) || secondScheme.equalsIgnoreCase(uri.getScheme());
            if (!schemeValid || StringUtils.isEmpty(uri.getHost()))
            {
                throw new ServiceException(message);
            }
        }
        catch (URISyntaxException ex)
        {
            throw new ServiceException(message);
        }
    }

    private void validateCredential(String authType, String credentialRef)
    {
        if ("NONE".equals(authType))
        {
            return;
        }
        if (StringUtils.isEmpty(credentialRef))
        {
            throw new ServiceException("当前认证类型必须配置连接凭据");
        }
        validateOptionalCredential(credentialRef);
        if ("BASIC".equals(authType) && !CREDENTIAL_REFERENCE.matcher(credentialRef).matches()
                && credentialRef.indexOf(':') <= 0)
        {
            throw new ServiceException("基础认证凭据格式应为用户名:密码");
        }
    }

    private void validateOptionalCredential(String credentialRef)
    {
        if (StringUtils.isEmpty(credentialRef))
        {
            return;
        }
        if (credentialRef.length() > MAX_CREDENTIAL_LENGTH)
        {
            throw new ServiceException("连接凭据不能超过4096个字符");
        }
        if (credentialRef.indexOf('\r') >= 0 || credentialRef.indexOf('\n') >= 0 || credentialRef.indexOf('\0') >= 0)
        {
            throw new ServiceException("连接凭据不能包含换行或空字符");
        }
        if ((credentialRef.startsWith("env:") || credentialRef.startsWith("sys:"))
                && !CREDENTIAL_REFERENCE.matcher(credentialRef).matches())
        {
            throw new ServiceException("环境变量或系统属性凭据引用格式不合法");
        }
    }

    private void validateTimezone(String timezone)
    {
        try
        {
            ZoneId.of(timezone);
            if (!ZoneId.getAvailableZoneIds().contains(timezone))
            {
                throw new ServiceException("默认时区必须是有效的IANA时区");
            }
        }
        catch (DateTimeException ex)
        {
            throw new ServiceException("默认时区必须是有效的IANA时区");
        }
    }

    private void validateEnabled(String enabled)
    {
        if (!"0".equals(enabled) && !"1".equals(enabled))
        {
            throw new ServiceException("启停状态不合法");
        }
    }

    private Integer intValue(Object input, Integer current, int defaultValue, int min, int max, String message)
    {
        int value;
        try
        {
            value = input == null || StringUtils.isEmpty(String.valueOf(input))
                    ? (current == null ? defaultValue : current) : Integer.parseInt(String.valueOf(input));
        }
        catch (NumberFormatException ex)
        {
            throw new ServiceException(message);
        }
        if (value < min || value > max)
        {
            throw new ServiceException(message);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value)
    {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private String firstNotEmpty(Object first, Object second)
    {
        String value = normalize(first);
        return StringUtils.isEmpty(value) ? normalize(second) : value;
    }

    private String normalize(Object value)
    {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String truncate(String value, int max)
    {
        if (value == null || value.length() <= max)
        {
            return value;
        }
        return value.substring(0, max);
    }
}
