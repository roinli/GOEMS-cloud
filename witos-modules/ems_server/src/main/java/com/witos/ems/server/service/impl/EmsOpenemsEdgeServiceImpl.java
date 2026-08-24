package com.witos.ems.server.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.auth.EmsDataScope;
import com.witos.ems.server.domain.entity.EmsCompany;
import com.witos.ems.server.domain.entity.EmsOpenemsEdge;
import com.witos.ems.server.domain.entity.EmsOpenemsEdgeCreateTask;
import com.witos.ems.server.domain.entity.EmsOpenemsEdgeCredential;
import com.witos.ems.server.domain.entity.EmsServerEndpoint;
import com.witos.ems.server.domain.entity.EmsStation;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeCreateTaskMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeCredentialMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeMapper;
import com.witos.ems.server.mapper.EmsServerEndpointMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.ems.server.openems.OpenemsCredentialCipher;
import com.witos.ems.server.openems.OpenemsJsonRpcClient;
import com.witos.ems.server.service.EmsOpenemsEdgeService;
import com.witos.ems.server.support.EmsPageSupport;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class EmsOpenemsEdgeServiceImpl implements EmsOpenemsEdgeService
{
    private static final Pattern REQUEST_NO = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Pattern EDGE_SUFFIX = Pattern.compile(".*\\d$");
    private static final String MASKED = "******";

    @Resource
    private EmsOpenemsEdgeMapper edgeMapper;

    @Resource
    private EmsAuthScopeService authScopeService;

    @Resource
    private EmsOpenemsEdgeCreateTaskMapper createTaskMapper;

    @Resource
    private EmsOpenemsEdgeCredentialMapper credentialMapper;

    @Resource
    private EmsServerEndpointMapper endpointMapper;

    @Resource
    private EmsCompanyMapper companyMapper;

    @Resource
    private EmsStationMapper stationMapper;

    @Resource
    private OpenemsJsonRpcClient openemsJsonRpcClient;

    @Resource
    private OpenemsCredentialCipher credentialCipher;

    @Resource
    private TransactionTemplate transactionTemplate;

    private final Map<String, Object> endpointLocks = new ConcurrentHashMap<String, Object>();

    @Override
    public IPage<Map<String, Object>> list(Map<String, String> query)
    {
        query = query == null ? new LinkedHashMap<String, String>() : query;
        Long tenantId = EmsRequestSupport.currentTenantId();
        LambdaQueryWrapper<EmsOpenemsEdge> wrapper = new LambdaQueryWrapper<EmsOpenemsEdge>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsEdge::getTenantId, tenantId)
                .eq(parseLong(query.get("endpointId")) != null, EmsOpenemsEdge::getEndpointId, parseLong(query.get("endpointId")))
                .like(StringUtils.isNotEmpty(query.get("edgeId")), EmsOpenemsEdge::getEdgeId, query.get("edgeId"))
                .like(StringUtils.isNotEmpty(query.get("edgeName")), EmsOpenemsEdge::getEdgeName, query.get("edgeName"))
                .eq(StringUtils.isNotEmpty(query.get("onlineStatus")), EmsOpenemsEdge::getOnlineStatus, query.get("onlineStatus"))
                .eq(StringUtils.isNotEmpty(query.get("sourceType")), EmsOpenemsEdge::getSourceType, query.get("sourceType"))
                .eq(parseLong(query.get("companyId")) != null, EmsOpenemsEdge::getCompanyId, parseLong(query.get("companyId")))
                .eq(parseLong(query.get("stationId")) != null, EmsOpenemsEdge::getStationId, parseLong(query.get("stationId")))
                .orderByDesc(EmsOpenemsEdge::getId);
        applyDataScope(wrapper, authScopeService.currentScope());
        IPage<EmsOpenemsEdge> source = edgeMapper.selectPage(EmsPageSupport.<EmsOpenemsEdge>page(), wrapper);
        Page<Map<String, Object>> result = new Page<Map<String, Object>>(source.getCurrent(), source.getSize(), source.getTotal());
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Set<String> credentialKeys = credentialKeys(source.getRecords(), tenantId);
        for (EmsOpenemsEdge edge : source.getRecords())
        {
            rows.add(toView(edge, credentialKeys.contains(credentialKey(tenantId, edge.getEndpointId(), edge.getEdgeId()))));
        }
        result.setRecords(rows);
        return result;
    }

    @Override
    public Map<String, Object> get(Long id)
    {
        EmsOpenemsEdge edge = requireEdge(id);
        return toView(edge, hasCredential(edge));
    }

    @Override
    public Map<String, Object> create(Map<String, Object> body)
    {
        if (body == null)
        {
            throw new ServiceException("控制器创建参数不能为空");
        }
        Long endpointId = EmsRequestSupport.requiredLong(body, "endpointId", "OpenEMS端点不能为空");
        EmsServerEndpoint endpoint = requireEndpoint(endpointId);
        String edgeName = normalize(body.get("edgeName"));
        if (StringUtils.isEmpty(edgeName) || edgeName.length() > 128)
        {
            throw new ServiceException("控制器名称不能为空且不能超过128个字符");
        }
        String requestNo = normalize(body.get("requestNo"));
        if (StringUtils.isEmpty(requestNo))
        {
            requestNo = UUID.randomUUID().toString().replace("-", "");
        }
        if (!REQUEST_NO.matcher(requestNo).matches())
        {
            throw new ServiceException("requestNo需为8-64位字母、数字、下划线或中划线");
        }
        Long tenantId = endpoint.getTenantId();
        String lockKey = tenantId + ":" + endpointId;
        Object lock = endpointLocks.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (lock)
        {
            try
            {
                return doCreate(tenantId, endpoint, edgeName, requestNo);
            }
            finally
            {
                endpointLocks.remove(lockKey, lock);
            }
        }
    }

    private Map<String, Object> doCreate(Long tenantId, EmsServerEndpoint endpoint, String edgeName, String requestNo)
    {
        EmsOpenemsEdgeCreateTask existingRequest = createTaskMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsEdgeCreateTask>()
                .eq(EmsOpenemsEdgeCreateTask::getTenantId, tenantId)
                .eq(EmsOpenemsEdgeCreateTask::getEndpointId, endpoint.getId())
                .eq(EmsOpenemsEdgeCreateTask::getRequestNo, requestNo)
                .last("limit 1"));
        if (existingRequest != null)
        {
            if ("SUCCESS".equals(existingRequest.getState()) && StringUtils.isNotEmpty(existingRequest.getBackendEdgeId()))
            {
                throw new ServiceException("该requestNo已创建成功，请勿重复提交");
            }
            throw new ServiceException("该requestNo已使用，请生成新的requestNo后再提交");
        }
        Long activeCount = createTaskMapper.selectCount(new LambdaQueryWrapper<EmsOpenemsEdgeCreateTask>()
                .eq(EmsOpenemsEdgeCreateTask::getTenantId, tenantId)
                .eq(EmsOpenemsEdgeCreateTask::getEndpointId, endpoint.getId())
                .in(EmsOpenemsEdgeCreateTask::getState, "CREATING", "PENDING_RECONCILIATION"));
        if (activeCount != null && activeCount > 0)
        {
            throw new ServiceException("该端点已有控制器创建任务正在处理或待核对");
        }

        String commentMarker = "EMS|tenant=" + tenantId + "|request=" + requestNo + "|name=" + edgeName;
        if (commentMarker.length() > 255)
        {
            throw new ServiceException("控制器名称过长，无法写入Backend核对标记");
        }
        EmsOpenemsEdgeCreateTask task = new EmsOpenemsEdgeCreateTask();
        task.setTenantId(tenantId);
        task.setRequestNo(requestNo);
        task.setEndpointId(endpoint.getId());
        task.setEdgeName(edgeName);
        task.setCommentMarker(commentMarker);
        task.setState("CREATING");
        task.setRequestedAt(new Date());
        task.setDelFlag("0");
        createTaskMapper.insert(task);

        Map<String, Object> backendResult;
        try
        {
            backendResult = openemsJsonRpcClient.createEdge(endpoint.getId(), commentMarker);
        }
        catch (RuntimeException ex)
        {
            if (isTimeout(ex))
            {
                updateTask(task, "PENDING_RECONCILIATION", "TIMEOUT", errorMessage(ex), null);
                throw new ServiceException("Backend创建请求超时，已进入待核对，禁止立即重复创建");
            }
            updateTask(task, "FAILED", "BACKEND_ERROR", errorMessage(ex), null);
            throw ex;
        }

        String edgeId = normalize(backendResult.get("edgeId"));
        String apiKey = normalize(backendResult.get("apiKey"));
        String setupPassword = normalize(backendResult.get("setupPassword"));
        if (StringUtils.isEmpty(edgeId) || StringUtils.isEmpty(apiKey) || StringUtils.isEmpty(setupPassword))
        {
            updateTask(task, "PENDING_RECONCILIATION", "INVALID_RESPONSE", "Backend返回缺少完整创建凭据", null);
            throw new ServiceException("Backend创建结果不完整，已进入待核对");
        }

        final EmsOpenemsEdge persisted;
        try
        {
            persisted = transactionTemplate.execute(status -> persistSuccess(tenantId, endpoint.getId(), edgeName,
                    commentMarker, edgeId, apiKey, setupPassword, task));
        }
        catch (RuntimeException ex)
        {
            updateTask(task, "PENDING_RECONCILIATION", "LOCAL_PERSIST_FAILED", errorMessage(ex), edgeId);
            throw new ServiceException("Backend已创建，但EMS入库失败，已进入待核对");
        }
        if (persisted == null)
        {
            updateTask(task, "PENDING_RECONCILIATION", "LOCAL_PERSIST_FAILED", "EMS控制器入库事务未返回结果", edgeId);
            throw new ServiceException("Backend已创建，但EMS入库失败，已进入待核对");
        }
        Map<String, Object> response = toView(persisted, true);
        response.put("requestNo", requestNo);
        response.put("apiKey", apiKey);
        response.put("setupPassword", setupPassword);
        response.put("credentialDisplay", "FULL_ON_CREATE_RESPONSE");
        return response;
    }

    private EmsOpenemsEdge persistSuccess(Long tenantId, Long endpointId, String edgeName, String commentMarker,
                                          String edgeId, String apiKey, String setupPassword, EmsOpenemsEdgeCreateTask task)
    {
        EmsOpenemsEdge edge = edgeMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsEdge>()
                .eq(EmsOpenemsEdge::getTenantId, tenantId)
                .eq(EmsOpenemsEdge::getEndpointId, endpointId)
                .eq(EmsOpenemsEdge::getEdgeId, edgeId)
                .last("limit 1"));
        if (edge == null)
        {
            edge = new EmsOpenemsEdge();
            edge.setTenantId(tenantId);
            edge.setEndpointId(endpointId);
            edge.setEdgeId(edgeId);
            edge.setDelFlag("0");
            edge.setOnlineStatus("OFFLINE");
            edge.setSourceType("EMS_CREATED");
            edge.setCompanyId(null);
            edge.setStationId(null);
            edge.setCreateBy(EmsRequestSupport.currentUsername());
            edgeMapper.insert(edge);
        }
        edge.setEdgeName(edgeName);
        edge.setSourceType("EMS_CREATED");
        edge.setOnlineStatus(StringUtils.isEmpty(edge.getOnlineStatus()) ? "OFFLINE" : edge.getOnlineStatus());
        edge.setCommentMarker(commentMarker);
        edge.setEdgeKey(EDGE_SUFFIX.matcher(edgeId).matches() ? edgeId.replaceAll(".*?(\\d+)$", "$1") : null);
        edge.setDataCapabilityStatus(EDGE_SUFFIX.matcher(edgeId).matches() ? "OK" : "TIMESERIES_UNAVAILABLE_EDGE_ID_FORMAT");
        edge.setLastSeenAt(new Date());
        edgeMapper.updateById(edge);

        EmsOpenemsEdgeCredential credential = credentialMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsEdgeCredential>()
                .eq(EmsOpenemsEdgeCredential::getTenantId, tenantId)
                .eq(EmsOpenemsEdgeCredential::getEndpointId, endpointId)
                .eq(EmsOpenemsEdgeCredential::getEdgeId, edgeId)
                .last("limit 1"));
        if (credential != null)
        {
            throw new ServiceException("OpenEMS控制器凭据记录已存在，拒绝覆盖");
        }
        credential = new EmsOpenemsEdgeCredential();
        credential.setTenantId(tenantId);
        credential.setEndpointId(endpointId);
        credential.setEdgeId(edgeId);
        credential.setApiKeyCiphertext(credentialCipher.encrypt(apiKey));
        credential.setSetupPasswordCiphertext(credentialCipher.encrypt(setupPassword));
        credential.setCredentialVersion(1);
        credential.setDelFlag("0");
        credentialMapper.insert(credential);

        task.setState("SUCCESS");
        task.setBackendEdgeId(edgeId);
        task.setFinishedAt(new Date());
        task.setErrorCode(null);
        task.setErrorMessage(null);
        createTaskMapper.updateById(task);
        return edge;
    }

    @Override
    public Map<String, Object> getCreateTask(String requestNo)
    {
        Long tenantId = EmsRequestSupport.currentTenantId();
        EmsOpenemsEdgeCreateTask task = createTaskMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsEdgeCreateTask>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsEdgeCreateTask::getTenantId, tenantId)
                .eq(EmsOpenemsEdgeCreateTask::getRequestNo, requestNo)
                .last("limit 1"));
        if (task == null)
        {
            throw new ServiceException("创建任务不存在");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", task.getId());
        result.put("requestNo", task.getRequestNo());
        result.put("endpointId", task.getEndpointId());
        result.put("edgeName", task.getEdgeName());
        result.put("commentMarker", task.getCommentMarker());
        result.put("state", task.getState());
        result.put("backendEdgeId", task.getBackendEdgeId());
        result.put("errorCode", task.getErrorCode());
        result.put("errorMessage", task.getErrorMessage());
        result.put("requestedAt", task.getRequestedAt());
        result.put("finishedAt", task.getFinishedAt());
        return result;
    }

    @Override
    public Map<String, Object> revealCredentials(Long id)
    {
        EmsOpenemsEdge edge = requireEdge(id);
        EmsOpenemsEdgeCredential credential = credentialMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsEdgeCredential>()
                .eq(EmsOpenemsEdgeCredential::getTenantId, edge.getTenantId())
                .eq(EmsOpenemsEdgeCredential::getEndpointId, edge.getEndpointId())
                .eq(EmsOpenemsEdgeCredential::getEdgeId, edge.getEdgeId())
                .last("limit 1"));
        if (credential == null)
        {
            throw new ServiceException("凭据不可查看：该控制器不是EMS创建或凭据未保存");
        }
        String apiKey = credentialCipher.decrypt(credential.getApiKeyCiphertext());
        String setupPassword = credentialCipher.decrypt(credential.getSetupPasswordCiphertext());
        JSONObject audit = StringUtils.isEmpty(credential.getDisplayAuditJson())
                ? new JSONObject() : JSON.parseObject(credential.getDisplayAuditJson());
        JSONArray views = audit.getJSONArray("views");
        if (views == null)
        {
            views = new JSONArray();
            audit.put("views", views);
        }
        JSONObject view = new JSONObject();
        view.put("username", EmsRequestSupport.currentUsername());
        view.put("viewedAt", LocalDateTime.now().toString());
        views.add(view);
        while (views.size() > 50)
        {
            views.remove(0);
        }
        credential.setDisplayAuditJson(JSON.toJSONString(audit));
        credentialMapper.updateById(credential);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("edgeId", edge.getEdgeId());
        result.put("apiKey", apiKey);
        result.put("setupPassword", setupPassword);
        result.put("viewedAt", view.getString("viewedAt"));
        return result;
    }

    private void updateTask(EmsOpenemsEdgeCreateTask task, String state, String errorCode, String errorMessage, String edgeId)
    {
        task.setState(state);
        task.setErrorCode(errorCode);
        task.setErrorMessage(errorMessage);
        task.setBackendEdgeId(edgeId);
        task.setFinishedAt("SUCCESS".equals(state) || "FAILED".equals(state) ? new Date() : null);
        createTaskMapper.updateById(task);
    }

    private boolean hasCredential(EmsOpenemsEdge edge)
    {
        return credentialMapper.selectCount(new LambdaQueryWrapper<EmsOpenemsEdgeCredential>()
                .eq(EmsOpenemsEdgeCredential::getTenantId, edge.getTenantId())
                .eq(EmsOpenemsEdgeCredential::getEndpointId, edge.getEndpointId())
                .eq(EmsOpenemsEdgeCredential::getEdgeId, edge.getEdgeId())) > 0;
    }

    private Set<String> credentialKeys(List<EmsOpenemsEdge> edges, Long tenantId)
    {
        Set<String> keys = new HashSet<String>();
        if (edges == null || edges.isEmpty())
        {
            return keys;
        }
        List<Long> endpointIds = new ArrayList<Long>();
        List<String> edgeIds = new ArrayList<String>();
        for (EmsOpenemsEdge edge : edges)
        {
            if (edge.getEndpointId() != null && !endpointIds.contains(edge.getEndpointId()))
            {
                endpointIds.add(edge.getEndpointId());
            }
            if (StringUtils.isNotEmpty(edge.getEdgeId()) && !edgeIds.contains(edge.getEdgeId()))
            {
                edgeIds.add(edge.getEdgeId());
            }
        }
        if (endpointIds.isEmpty() || edgeIds.isEmpty())
        {
            return keys;
        }
        List<EmsOpenemsEdgeCredential> credentials = credentialMapper.selectList(new LambdaQueryWrapper<EmsOpenemsEdgeCredential>()
                .eq(EmsOpenemsEdgeCredential::getTenantId, tenantId)
                .in(EmsOpenemsEdgeCredential::getEndpointId, endpointIds)
                .in(EmsOpenemsEdgeCredential::getEdgeId, edgeIds));
        for (EmsOpenemsEdgeCredential credential : credentials)
        {
            keys.add(credentialKey(tenantId, credential.getEndpointId(), credential.getEdgeId()));
        }
        return keys;
    }

    private String credentialKey(Long tenantId, Long endpointId, String edgeId)
    {
        return tenantId + ":" + endpointId + ":" + edgeId;
    }

    private Map<String, Object> toView(EmsOpenemsEdge edge, boolean hasCredential)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", edge.getId());
        row.put("endpointId", edge.getEndpointId());
        row.put("edgeId", edge.getEdgeId());
        row.put("edgeKey", edge.getEdgeKey());
        row.put("edgeName", edge.getEdgeName());
        row.put("sourceType", edge.getSourceType());
        row.put("companyId", edge.getCompanyId());
        row.put("stationId", edge.getStationId());
        EmsCompany company = edge.getCompanyId() == null ? null : companyMapper.selectById(edge.getCompanyId());
        EmsStation station = edge.getStationId() == null ? null : stationMapper.selectById(edge.getStationId());
        row.put("companyName", company == null ? null : company.getCompanyName());
        row.put("stationName", station == null ? null : station.getStationName());
        row.put("onlineStatus", edge.getOnlineStatus());
        row.put("lastHeartbeatAt", edge.getLastHeartbeatAt());
        row.put("lastSeenAt", edge.getLastSeenAt());
        row.put("lastSyncAt", edge.getLastSyncAt());
        row.put("dataCapabilityStatus", edge.getDataCapabilityStatus());
        row.put("commentMarker", edge.getCommentMarker());
        row.put("apiKey", hasCredential ? MASKED : null);
        row.put("setupPassword", hasCredential ? MASKED : null);
        row.put("credentialViewable", hasCredential);
        row.put("credentialHint", hasCredential ? "详情可查看完整凭据" : "凭据不可查看");
        return row;
    }

    private EmsOpenemsEdge requireEdge(Long id)
    {
        if (id == null)
        {
            throw new ServiceException("控制器不能为空");
        }
        EmsOpenemsEdge edge = edgeMapper.selectById(id);
        if (edge == null || (!EmsRequestSupport.isPlatformAdmin()
                && !Objects.equals(edge.getTenantId(), EmsRequestSupport.currentTenantId())))
        {
            throw new ServiceException("控制器不存在或无权访问");
        }
        ensureDataScope(edge.getCompanyId(), edge.getStationId());
        return edge;
    }

    private void applyDataScope(LambdaQueryWrapper<EmsOpenemsEdge> wrapper, EmsDataScope scope)
    {
        if (scope == null || !scope.isScopeRestricted())
        {
            return;
        }
        boolean hasCompanies = scope.getCompanyIds() != null && !scope.getCompanyIds().isEmpty();
        boolean hasStations = scope.getStationIds() != null && !scope.getStationIds().isEmpty();
        if (!hasCompanies && !hasStations)
        {
            wrapper.apply("1 = 0");
            return;
        }
        wrapper.and(item -> {
            if (hasCompanies)
            {
                item.in(EmsOpenemsEdge::getCompanyId, scope.getCompanyIds());
            }
            if (hasStations)
            {
                if (hasCompanies)
                {
                    item.or();
                }
                item.in(EmsOpenemsEdge::getStationId, scope.getStationIds());
            }
        });
    }

    private void ensureDataScope(Long companyId, Long stationId)
    {
        EmsDataScope scope = authScopeService.currentScope();
        if (scope == null || !scope.isScopeRestricted())
        {
            return;
        }
        boolean companyAllowed = companyId != null && scope.getCompanyIds() != null && scope.getCompanyIds().contains(companyId);
        boolean stationAllowed = stationId != null && scope.getStationIds() != null && scope.getStationIds().contains(stationId);
        if (!companyAllowed && !stationAllowed)
        {
            throw new ServiceException("控制器超出当前公司或电站授权范围");
        }
    }

    private EmsServerEndpoint requireEndpoint(Long endpointId)
    {
        EmsServerEndpoint endpoint = endpointMapper.selectById(endpointId);
        if (endpoint == null || (!EmsRequestSupport.isPlatformAdmin()
                && !Objects.equals(endpoint.getTenantId(), EmsRequestSupport.currentTenantId()))
                || !"TENANT".equals(endpoint.getScopeType()) || !"0".equals(endpoint.getEnabled()))
        {
            throw new ServiceException("OpenEMS端点不存在、无权访问或未启用");
        }
        return endpoint;
    }

    private Long parseLong(String value)
    {
        return StringUtils.isEmpty(value) ? null : Long.valueOf(value);
    }

    private String normalize(Object value)
    {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean isTimeout(RuntimeException ex)
    {
        String message = errorMessage(ex).toLowerCase();
        return message.contains("超时") || message.contains("timeout") || message.contains("timed out");
    }

    private String errorMessage(RuntimeException ex)
    {
        return ex.getMessage() == null ? "OpenEMS请求失败" : ex.getMessage();
    }
}
