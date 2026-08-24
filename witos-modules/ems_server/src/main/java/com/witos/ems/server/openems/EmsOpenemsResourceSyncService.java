package com.witos.ems.server.openems;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.domain.entity.EmsOpenemsAppComponent;
import com.witos.ems.server.domain.entity.EmsOpenemsAppInstance;
import com.witos.ems.server.domain.entity.EmsOpenemsAppRelation;
import com.witos.ems.server.domain.entity.EmsOpenemsComponent;
import com.witos.ems.server.domain.entity.EmsOpenemsComponentRelation;
import com.witos.ems.server.domain.entity.EmsOpenemsEdge;
import com.witos.ems.server.domain.entity.EmsOpenemsEdgeCreateTask;
import com.witos.ems.server.domain.entity.EmsOpenemsDevice;
import com.witos.ems.server.domain.entity.EmsServerEndpoint;
import com.witos.ems.server.mapper.EmsOpenemsAppComponentMapper;
import com.witos.ems.server.mapper.EmsOpenemsAppInstanceMapper;
import com.witos.ems.server.mapper.EmsOpenemsAppRelationMapper;
import com.witos.ems.server.mapper.EmsOpenemsComponentMapper;
import com.witos.ems.server.mapper.EmsOpenemsComponentRelationMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeCreateTaskMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeMapper;
import com.witos.ems.server.mapper.EmsOpenemsDeviceMapper;
import com.witos.ems.server.mapper.EmsServerEndpointMapper;
import com.witos.ems.server.service.EmsOpenemsProvisionDispatchService;
import com.witos.ems.server.service.EmsOpenemsBindingService;
import com.witos.ems.server.service.EmsOpenemsBusinessProjectionService;
import com.witos.ems.server.service.EmsOpenemsCapabilityService;
import com.witos.ems.server.support.EmsRequestSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class EmsOpenemsResourceSyncService
{
    private static final Pattern EDGE_SUFFIX = Pattern.compile(".*?(\\d+)$");
    private static final long STALE_AFTER_MILLIS = 10L * 60L * 1000L;
    private static final Set<String> DEVICE_NATURES = new HashSet<String>(java.util.Arrays.asList(
            "io.openems.edge.meter.api.ElectricityMeter",
            "io.openems.edge.ess.api.SymmetricEss",
            "io.openems.edge.battery.api.Battery",
            "io.openems.edge.batteryinverter.api.SymmetricBatteryInverter",
            "io.openems.edge.ess.dccharger.api.EssDcCharger",
            "io.openems.edge.evcs.api.Evcs",
            "io.openems.edge.evcs.api.DeprecatedEvcs",
            "io.openems.edge.evse.api.chargepoint.EvseChargePoint",
            "io.openems.edge.evse.api.electricvehicle.EvseElectricVehicle",
            "io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter",
            "io.openems.edge.heat.api.Heat",
            "io.openems.edge.heat.api.ManagedHeatElement",
            "io.openems.edge.io.api.AnalogOutput",
            "io.openems.edge.io.api.DigitalInput",
            "io.openems.edge.io.api.DigitalOutput",
            "io.openems.edge.thermometer.api.Thermometer"));

    @Resource
    private EmsServerEndpointMapper endpointMapper;

    @Resource
    private EmsOpenemsEdgeMapper edgeMapper;

    @Resource
    private EmsOpenemsDeviceMapper deviceMapper;

    @Resource
    private EmsOpenemsEdgeCreateTaskMapper createTaskMapper;

    @Resource
    private EmsOpenemsComponentMapper componentMapper;

    @Resource
    private EmsOpenemsComponentRelationMapper componentRelationMapper;

    @Resource
    private EmsOpenemsAppInstanceMapper appInstanceMapper;

    @Resource
    private EmsOpenemsAppComponentMapper appComponentMapper;

    @Resource
    private EmsOpenemsAppRelationMapper appRelationMapper;

    @Resource
    private OpenemsJsonRpcClient openemsJsonRpcClient;

    @Resource
    private EmsOpenemsProvisionDispatchService provisionDispatchService;

    @Resource
    private EmsOpenemsBindingService bindingService;

    @Resource
    private EmsOpenemsBusinessProjectionService businessProjectionService;

    @Resource
    private EmsOpenemsCapabilityService capabilityService;

    @Resource
    private TransactionTemplate transactionTemplate;

    public int syncHeartbeatCurrentTenant()
    {
        return syncCurrentTenant(false);
    }

    public int syncFullCurrentTenant()
    {
        return syncCurrentTenant(true);
    }

    private int syncCurrentTenant(boolean full)
    {
        Long tenantId = EmsRequestSupport.currentTenantId();
        List<EmsServerEndpoint> endpoints = endpointMapper.selectList(new LambdaQueryWrapper<EmsServerEndpoint>()
                .eq(EmsServerEndpoint::getTenantId, tenantId)
                .eq(EmsServerEndpoint::getScopeType, "TENANT")
                .eq(EmsServerEndpoint::getEnabled, "0"));
        int affected = 0;
        for (EmsServerEndpoint endpoint : endpoints)
        {
            try
            {
                List<Map<String, Object>> backendEdges = openemsJsonRpcClient.listEdges(endpoint.getId());
                if (backendEdges.isEmpty())
                {
                    markEndpointStale(endpoint, tenantId);
                    continue;
                }
                EdgeSyncResult edgeResult = syncEdges(endpoint, tenantId, backendEdges, full);
                affected += edgeResult.affected;
                if (full || !edgeResult.newlyOnlineRows.isEmpty())
                {
                    affected += syncOnlineEdgeResources(endpoint, tenantId,
                            full ? backendEdges : edgeResult.newlyOnlineRows);
                }
                affected += dispatchOnlineEdges(endpoint, tenantId);
            }
            catch (Exception ex)
            {
                markEndpointStale(endpoint, tenantId);
                log.error("OpenEMS resource sync failed, tenantId={}, endpointId={}, full={}",
                        tenantId, endpoint.getId(), full, ex);
            }
        }
        return affected;
    }

    private int dispatchOnlineEdges(EmsServerEndpoint endpoint, Long tenantId)
    {
        int affected = 0;
        List<EmsOpenemsEdge> onlineEdges = edgeMapper.selectList(new LambdaQueryWrapper<EmsOpenemsEdge>()
                .eq(EmsOpenemsEdge::getTenantId, tenantId)
                .eq(EmsOpenemsEdge::getEndpointId, endpoint.getId())
                .eq(EmsOpenemsEdge::getOnlineStatus, "ONLINE")
                .eq(EmsOpenemsEdge::getDelFlag, "0"));
        for (EmsOpenemsEdge edge : onlineEdges)
        {
            affected += provisionDispatchService.dispatchPendingCurrentTenant(endpoint.getId(), edge.getEdgeId());
        }
        return affected;
    }

    private EdgeSyncResult syncEdges(EmsServerEndpoint endpoint, Long tenantId,
                                     List<Map<String, Object>> rows, boolean full)
    {
        Date now = new Date();
        Map<String, EmsOpenemsEdge> existing = new HashMap<String, EmsOpenemsEdge>();
        for (EmsOpenemsEdge edge : edgeMapper.selectList(new LambdaQueryWrapper<EmsOpenemsEdge>()
                .eq(EmsOpenemsEdge::getTenantId, tenantId)
                .eq(EmsOpenemsEdge::getEndpointId, endpoint.getId())))
        {
            existing.put(edge.getEdgeId(), edge);
        }
        Map<String, EmsOpenemsEdgeCreateTask> pendingByComment = pendingTasks(endpoint.getId(), tenantId);
        Set<String> seen = new HashSet<String>();
        List<Map<String, Object>> newlyOnlineRows = new ArrayList<Map<String, Object>>();
        int affected = 0;
        boolean writeFailed = false;
        for (Map<String, Object> row : rows)
        {
            String edgeId = stringValue(row.get("id"));
            if (StringUtils.isEmpty(edgeId))
            {
                continue;
            }
            seen.add(edgeId);
            String comment = stringValue(row.get("comment"));
            EmsOpenemsEdge edge = existing.get(edgeId);
            String previousStatus = edge == null ? null : edge.getOnlineStatus();
            EmsOpenemsEdgeCreateTask reconciledTask = pendingByComment.get(comment);
            try
            {
                EmsOpenemsEdge syncedEdge = transactionTemplate.execute(status ->
                        syncSingleEdge(endpoint, tenantId, row, edge, reconciledTask, now));
                if (syncedEdge == null)
                {
                    throw new IllegalStateException("Edge同步事务未返回结果");
                }
                existing.put(edgeId, syncedEdge);
                if (reconciledTask != null)
                {
                    pendingByComment.remove(comment);
                }
                if ("ONLINE".equals(syncedEdge.getOnlineStatus()) && !"ONLINE".equals(previousStatus))
                {
                    newlyOnlineRows.add(row);
                }
                affected++;
            }
            catch (Exception ex)
            {
                writeFailed = true;
                log.error("OpenEMS Edge mirror write failed, tenantId={}, endpointId={}, edgeId={}",
                        tenantId, endpoint.getId(), edgeId, ex);
            }
        }
        for (EmsOpenemsEdge edge : existing.values())
        {
            if (!seen.contains(edge.getEdgeId()))
            {
                try
                {
                    transactionTemplate.executeWithoutResult(status -> {
                        edge.setOnlineStatus("MISSING");
                        edge.setLastSyncAt(now);
                        edgeMapper.updateById(edge);
                        if (full)
                        {
                            markEdgeResourcesMissing(tenantId, endpoint.getId(), edge.getEdgeId());
                        }
                    });
                    affected++;
                }
                catch (Exception ex)
                {
                    writeFailed = true;
                    log.error("OpenEMS missing Edge mirror write failed, tenantId={}, endpointId={}, edgeId={}",
                            tenantId, endpoint.getId(), edge.getEdgeId(), ex);
                }
            }
        }
        if (full && !writeFailed)
        {
            for (EmsOpenemsEdgeCreateTask unresolved : pendingByComment.values())
            {
                transactionTemplate.executeWithoutResult(status -> {
                    unresolved.setState("FAILED");
                    unresolved.setErrorCode("RECONCILIATION_NOT_FOUND");
                    unresolved.setErrorMessage("Backend全量同步未找到对应comment标记，可重新创建");
                    unresolved.setFinishedAt(now);
                    createTaskMapper.updateById(unresolved);
                });
            }
        }
        return new EdgeSyncResult(affected, newlyOnlineRows);
    }

    private EmsOpenemsEdge syncSingleEdge(EmsServerEndpoint endpoint, Long tenantId, Map<String, Object> row,
                                           EmsOpenemsEdge existing, EmsOpenemsEdgeCreateTask reconciledTask, Date now)
    {
        String edgeId = stringValue(row.get("id"));
        String comment = stringValue(row.get("comment"));
        EmsOpenemsEdge edge = existing;
        if (edge == null)
        {
            edge = new EmsOpenemsEdge();
            edge.setTenantId(tenantId);
            edge.setEndpointId(endpoint.getId());
            edge.setEdgeId(edgeId);
            edge.setSourceType("BACKEND_SYNCED");
            edge.setCompanyId(null);
            edge.setStationId(null);
            edge.setDelFlag("0");
            edgeMapper.insert(edge);
        }
        if (StringUtils.isEmpty(edge.getEdgeName()))
        {
            edge.setEdgeName(reconciledTask != null ? reconciledTask.getEdgeName()
                    : (StringUtils.isEmpty(comment) ? edgeId : comment));
        }
        edge.setOnlineStatus(booleanValue(row.get("isOnline"), booleanValue(row.get("online"), false))
                ? "ONLINE" : "OFFLINE");
        Date heartbeat = parseDate(row.get("lastmessage"));
        if (heartbeat != null)
        {
            edge.setLastHeartbeatAt(heartbeat);
        }
        edge.setLastSeenAt(now);
        edge.setLastSyncAt(now);
        edge.setCommentMarker(comment);
        Matcher matcher = EDGE_SUFFIX.matcher(edgeId);
        boolean timeseriesSupported = matcher.matches();
        edge.setEdgeKey(timeseriesSupported ? matcher.group(1) : null);
        edge.setDataCapabilityStatus(timeseriesSupported ? "OK" : "TIMESERIES_UNAVAILABLE_EDGE_ID_FORMAT");
        edge.setRawMetadataJson(JSON.toJSONString(row));
        edgeMapper.updateById(edge);
        if (!"ONLINE".equals(edge.getOnlineStatus()) && deviceMapper != null)
        {
            markEdgeDevicesOffline(tenantId, endpoint.getId(), edgeId, now);
        }
        if (reconciledTask != null)
        {
            completeReconciliation(reconciledTask, edgeId, now);
        }
        return edge;
    }

    private void markEdgeDevicesOffline(Long tenantId, Long endpointId, String edgeId, Date now)
    {
        List<EmsOpenemsDevice> devices = deviceMapper.selectList(new LambdaQueryWrapper<EmsOpenemsDevice>()
                .eq(EmsOpenemsDevice::getTenantId, tenantId)
                .eq(EmsOpenemsDevice::getEndpointId, endpointId)
                .eq(EmsOpenemsDevice::getEdgeId, edgeId)
                .eq(EmsOpenemsDevice::getStatus, "ACTIVE")
                .eq(EmsOpenemsDevice::getDelFlag, "0"));
        for (EmsOpenemsDevice device : devices)
        {
            device.setStatus("OFFLINE");
            device.setUpdateTime(now);
            deviceMapper.updateById(device);
            if (businessProjectionService != null) businessProjectionService.syncDevice(device);
        }
    }

    private int syncOnlineEdgeResources(EmsServerEndpoint endpoint, Long tenantId, List<Map<String, Object>> backendEdges)
    {
        int affected = 0;
        for (Map<String, Object> row : backendEdges)
        {
            String edgeId = stringValue(row.get("id"));
            boolean online = booleanValue(row.get("isOnline"), booleanValue(row.get("online"), false));
            if (StringUtils.isEmpty(edgeId) || !online)
            {
                continue;
            }
            Map<String, Object> config;
            try
            {
                config = openemsJsonRpcClient.getEdgeConfig(endpoint.getId(), edgeId);
            }
            catch (Exception ex)
            {
                markComponentsStaleIfExpired(tenantId, endpoint.getId(), edgeId);
                markAppsStaleIfExpired(tenantId, endpoint.getId(), edgeId);
                log.warn("OpenEMS Edge config sync failed, tenantId={}, endpointId={}, edgeId={}, error={}",
                        tenantId, endpoint.getId(), edgeId, ex.getMessage());
                continue;
            }
            try
            {
                Integer componentAffected = transactionTemplate.execute(status ->
                        syncComponents(tenantId, endpoint.getId(), edgeId, config));
                affected += componentAffected == null ? 0 : componentAffected;
            }
            catch (Exception ex)
            {
                log.error("OpenEMS Component mirror write failed, tenantId={}, endpointId={}, edgeId={}",
                        tenantId, endpoint.getId(), edgeId, ex);
                continue;
            }
            try
            {
                Map<String, Object> appSnapshot = openemsJsonRpcClient.getAppSnapshot(endpoint.getId(), edgeId);
                Integer appAffected = transactionTemplate.execute(status -> syncApps(tenantId, endpoint.getId(), edgeId,
                        appSnapshot, componentIds(config), componentFactories(config)));
                affected += appAffected == null ? 0 : appAffected;
            }
            catch (Exception ex)
            {
                markAppsStaleIfExpired(tenantId, endpoint.getId(), edgeId);
                log.warn("OpenEMS App sync skipped, tenantId={}, endpointId={}, edgeId={}, error={}",
                        tenantId, endpoint.getId(), edgeId, ex.getMessage());
            }
            refreshEdgeCapabilities(endpoint.getId(), tenantId, edgeId);
        }
        return affected;
    }

    private void refreshEdgeCapabilities(Long endpointId, Long tenantId, String edgeId)
    {
        try
        {
            EmsOpenemsEdge edge = edgeMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsEdge>()
                    .eq(EmsOpenemsEdge::getTenantId, tenantId)
                    .eq(EmsOpenemsEdge::getEndpointId, endpointId)
                    .eq(EmsOpenemsEdge::getEdgeId, edgeId)
                    .eq(EmsOpenemsEdge::getDelFlag, "0")
                    .last("limit 1"));
            if (edge != null && capabilityService != null)
            {
                capabilityService.refresh(edge.getId());
            }
        }
        catch (Exception ex)
        {
            log.warn("OpenEMS capability refresh skipped, tenantId={}, endpointId={}, edgeId={}, error={}",
                    tenantId, endpointId, edgeId, ex.getMessage());
        }
    }

    private int syncComponents(Long tenantId, Long endpointId, String edgeId, Map<String, Object> config)
    {
        JSONObject root = JSON.parseObject(JSON.toJSONString(config));
        JSONObject components = root == null ? null : root.getJSONObject("components");
        if (components == null)
        {
            return 0;
        }
        JSONObject factories = root.getJSONObject("factories");
        markComponentsMissing(tenantId, endpointId, edgeId);
        Set<String> deviceComponentIds = new HashSet<String>();
        Map<String, EmsOpenemsComponent> existing = new HashMap<String, EmsOpenemsComponent>();
        for (EmsOpenemsComponent component : componentMapper.selectList(new LambdaQueryWrapper<EmsOpenemsComponent>()
                .eq(EmsOpenemsComponent::getTenantId, tenantId)
                .eq(EmsOpenemsComponent::getEndpointId, endpointId)
                .eq(EmsOpenemsComponent::getEdgeId, edgeId)))
        {
            existing.put(component.getComponentId(), component);
        }
        Date now = new Date();
        for (Map.Entry<String, Object> entry : components.entrySet())
        {
            String componentId = entry.getKey();
            JSONObject raw = objectValue(entry.getValue());
            EmsOpenemsComponent component = existing.get(componentId);
            if (component == null)
            {
                component = new EmsOpenemsComponent();
                component.setTenantId(tenantId);
                component.setEndpointId(endpointId);
                component.setEdgeId(edgeId);
                component.setComponentId(componentId);
                component.setDelFlag("0");
            }
            String factoryPid = raw.getString("factoryId");
            JSONArray natureIds = factories == null || factories.getJSONObject(factoryPid) == null
                    ? null : factories.getJSONObject(factoryPid).getJSONArray("natureIds");
            component.setFactoryPid(factoryPid);
            component.setComponentType(natureIds == null || natureIds.isEmpty() ? null : natureIds.getString(0));
            component.setNatureJson(natureIds == null ? null : natureIds.toJSONString());
            component.setAlias(raw.getString("alias"));
            component.setStatus("ACTIVE");
            component.setLastSeenAt(now);
            component.setConfigHash(sha256(raw.toJSONString()));
            component.setRawJson(raw.toJSONString());
            if (component.getId() == null)
            {
                componentMapper.insert(component);
            }
            else
            {
                componentMapper.updateById(component);
            }
            if (deviceMapper != null && isDeviceComponent(factoryPid, natureIds))
            {
                deviceComponentIds.add(componentId);
                materializeDevice(tenantId, endpointId, edgeId, component, raw, now);
            }
        }
        if (deviceMapper != null) markNonDeviceComponentsUnsupported(tenantId, endpointId, edgeId,
                deviceComponentIds, now);
        if (deviceMapper != null) markDevicesOfflineForMissingComponents(tenantId, endpointId, edgeId, now);
        syncComponentRelations(tenantId, endpointId, edgeId, components);
        return components.size();
    }

    private void markDevicesOfflineForMissingComponents(Long tenantId, Long endpointId, String edgeId, Date now)
    {
        List<EmsOpenemsDevice> devices = deviceMapper.selectList(new LambdaQueryWrapper<EmsOpenemsDevice>()
                .eq(EmsOpenemsDevice::getTenantId, tenantId)
                .eq(EmsOpenemsDevice::getEndpointId, endpointId)
                .eq(EmsOpenemsDevice::getEdgeId, edgeId)
                .eq(EmsOpenemsDevice::getSourceType, "BACKEND_SYNCED")
                .eq(EmsOpenemsDevice::getDelFlag, "0"));
        for (EmsOpenemsDevice device : devices)
        {
            EmsOpenemsComponent component = componentMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsComponent>()
                    .eq(EmsOpenemsComponent::getTenantId, tenantId)
                    .eq(EmsOpenemsComponent::getEndpointId, endpointId)
                    .eq(EmsOpenemsComponent::getEdgeId, edgeId)
                    .eq(EmsOpenemsComponent::getComponentId, device.getPrimaryComponentId())
                    .last("limit 1"));
            if (component != null && "MISSING".equals(component.getStatus()) && !"DISABLED".equals(device.getStatus()))
            {
                device.setStatus("OFFLINE");
                device.setUpdateTime(now);
                deviceMapper.updateById(device);
                if (businessProjectionService != null) businessProjectionService.syncDevice(device);
            }
        }
    }

    private void materializeDevice(Long tenantId, Long endpointId, String edgeId, EmsOpenemsComponent component,
                                   JSONObject raw, Date now)
    {
        EmsOpenemsDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsDevice>()
                .eq(EmsOpenemsDevice::getTenantId, tenantId)
                .eq(EmsOpenemsDevice::getEndpointId, endpointId)
                .eq(EmsOpenemsDevice::getEdgeId, edgeId)
                .eq(EmsOpenemsDevice::getPrimaryComponentId, component.getComponentId())
                .eq(EmsOpenemsDevice::getDelFlag, "0")
                .last("limit 1"));
        boolean created = device == null;
        if (created)
        {
            device = new EmsOpenemsDevice();
            device.setTenantId(tenantId);
            device.setEndpointId(endpointId);
            device.setEdgeId(edgeId);
            device.setResourceGroupId(component.getComponentId());
            device.setPrimaryComponentId(component.getComponentId());
            device.setSourceType("BACKEND_SYNCED");
            device.setCompanyId(null);
            device.setStationId(null);
            device.setDelFlag("0");
            device.setCreateTime(now);
        }
        if (StringUtils.isEmpty(device.getDisplayName()))
        {
            device.setDisplayName(StringUtils.isEmpty(component.getAlias()) ? component.getComponentId() : component.getAlias());
        }
        device.setDeviceType(deviceType(component.getFactoryPid(), component.getNatureJson()));
        if (!"DISABLED".equals(device.getStatus()))
        {
            device.setStatus("ACTIVE");
        }
        device.setLastSeenAt(now);
        device.setRawJson(raw.toJSONString());
        device.setUpdateTime(now);
        if (created) deviceMapper.insert(device); else deviceMapper.updateById(device);
        if (created)
        {
            if (bindingService != null) bindingService.inheritNewDevice(device.getId(), now, "INHERITED");
            device = deviceMapper.selectById(device.getId());
        }
        if (businessProjectionService != null) businessProjectionService.syncDevice(device);
    }

    private void markNonDeviceComponentsUnsupported(Long tenantId, Long endpointId, String edgeId,
                                                    Set<String> deviceComponentIds, Date now)
    {
        List<EmsOpenemsDevice> devices = deviceMapper.selectList(new LambdaQueryWrapper<EmsOpenemsDevice>()
                .eq(EmsOpenemsDevice::getTenantId, tenantId)
                .eq(EmsOpenemsDevice::getEndpointId, endpointId)
                .eq(EmsOpenemsDevice::getEdgeId, edgeId)
                .eq(EmsOpenemsDevice::getSourceType, "BACKEND_SYNCED")
                .eq(EmsOpenemsDevice::getDelFlag, "0"));
        for (EmsOpenemsDevice device : devices)
        {
            if (!deviceComponentIds.contains(device.getPrimaryComponentId())
                    && !"DISABLED".equals(device.getStatus()) && !"UNSUPPORTED".equals(device.getStatus()))
            {
                device.setStatus("UNSUPPORTED");
                device.setUpdateTime(now);
                deviceMapper.updateById(device);
                if (businessProjectionService != null) businessProjectionService.syncDevice(device);
            }
        }
    }

    boolean isDeviceComponent(String factoryPid, JSONArray natureIds)
    {
        String factory = String.valueOf(factoryPid);
        if (factory.matches("^(Bridge|Scheduler|Controller|Host|Timedata|Core|Predictor|Persistence|Meta|Alerting)\\..*"))
        {
            return false;
        }
        if (natureIds == null)
        {
            return false;
        }
        for (Object natureId : natureIds)
        {
            if (DEVICE_NATURES.contains(String.valueOf(natureId)))
            {
                return true;
            }
        }
        return false;
    }

    String deviceType(String factoryPid, String natureJson)
    {
        String value = String.valueOf(natureJson);
        if (value.contains("io.openems.edge.evcs.api.Evcs")
                || value.contains("io.openems.edge.evcs.api.DeprecatedEvcs")
                || value.contains("io.openems.edge.evse.api.chargepoint.EvseChargePoint")
                || value.contains("io.openems.edge.ess.dccharger.api.EssDcCharger")) return "CHARGER";
        if (value.contains("io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter")) return "INVERTER";
        if (value.contains("io.openems.edge.batteryinverter.api.SymmetricBatteryInverter")) return "PCS";
        if (value.contains("io.openems.edge.ess.api.SymmetricEss")) return "ESS";
        if (value.contains("io.openems.edge.battery.api.Battery")) return "BATTERY";
        if (value.contains("io.openems.edge.meter.api.ElectricityMeter")) return "METER";
        return "OTHER";
    }

    private void syncComponentRelations(Long tenantId, Long endpointId, String edgeId, JSONObject components)
    {
        componentRelationMapper.update(null, new LambdaUpdateWrapper<EmsOpenemsComponentRelation>()
                .set(EmsOpenemsComponentRelation::getStatus, "MISSING")
                .eq(EmsOpenemsComponentRelation::getTenantId, tenantId)
                .eq(EmsOpenemsComponentRelation::getEndpointId, endpointId)
                .eq(EmsOpenemsComponentRelation::getEdgeId, edgeId));
        Map<String, String> factoryById = new HashMap<String, String>();
        for (Map.Entry<String, Object> entry : components.entrySet())
        {
            factoryById.put(entry.getKey(), objectValue(entry.getValue()).getString("factoryId"));
        }
        for (Map.Entry<String, Object> entry : components.entrySet())
        {
            JSONObject component = objectValue(entry.getValue());
            Map<String, String> references = new LinkedHashMap<String, String>();
            collectComponentReferences(component.get("properties"), "properties", factoryById.keySet(), references);
            Map<String, List<String>> pathsByRelation = new LinkedHashMap<String, List<String>>();
            for (Map.Entry<String, String> reference : references.entrySet())
            {
                if (entry.getKey().equals(reference.getValue()))
                {
                    continue;
                }
                String childFactory = factoryById.get(reference.getValue());
                String parentFactory = factoryById.get(entry.getKey());
                String relationType = childFactory != null && childFactory.startsWith("Bridge.") ? "BRIDGE"
                        : (parentFactory != null && parentFactory.startsWith("Scheduler.") ? "SCHEDULER" : "USES");
                String relationKey = reference.getValue() + "\u0000" + relationType;
                pathsByRelation.computeIfAbsent(relationKey, ignored -> new ArrayList<String>())
                        .add(reference.getKey());
            }
            for (Map.Entry<String, List<String>> relation : pathsByRelation.entrySet())
            {
                int separator = relation.getKey().indexOf('\u0000');
                String childId = relation.getKey().substring(0, separator);
                String relationType = relation.getKey().substring(separator + 1);
                upsertComponentRelation(tenantId, endpointId, edgeId, entry.getKey(), childId,
                        relationType, relation.getValue());
            }
        }
    }

    private int syncApps(Long tenantId, Long endpointId, String edgeId, Map<String, Object> snapshot,
                         Set<String> componentIds, Map<String, String> componentFactories)
    {
        JSONObject root = JSON.parseObject(JSON.toJSONString(snapshot));
        JSONArray instances = root == null ? null : root.getJSONArray("instances");
        if (instances == null)
        {
            return 0;
        }
        markAppsMissing(tenantId, endpointId, edgeId);
        Map<String, EmsOpenemsAppInstance> existing = new HashMap<String, EmsOpenemsAppInstance>();
        for (EmsOpenemsAppInstance app : appInstanceMapper.selectList(new LambdaQueryWrapper<EmsOpenemsAppInstance>()
                .eq(EmsOpenemsAppInstance::getTenantId, tenantId)
                .eq(EmsOpenemsAppInstance::getEndpointId, endpointId)
                .eq(EmsOpenemsAppInstance::getEdgeId, edgeId)))
        {
            existing.put(app.getInstanceId(), app);
        }
        Date now = new Date();
        for (Object item : instances)
        {
            JSONObject raw = objectValue(item);
            String instanceId = raw.getString("instanceId");
            if (StringUtils.isEmpty(instanceId))
            {
                continue;
            }
            EmsOpenemsAppInstance app = existing.get(instanceId);
            if (app == null)
            {
                app = new EmsOpenemsAppInstance();
                app.setTenantId(tenantId);
                app.setEndpointId(endpointId);
                app.setEdgeId(edgeId);
                app.setInstanceId(instanceId);
                app.setDelFlag("0");
            }
            app.setAppId(raw.getString("appId"));
            app.setAlias(raw.getString("alias"));
            app.setPropertiesJson(jsonString(raw.get("properties")));
            JSONObject summary = raw.getJSONObject("appSummary");
            app.setWarningsJson(summary == null ? null : jsonString(summary.get("status")));
            app.setStatus("ACTIVE");
            app.setLastSeenAt(now);
            app.setRawJson(raw.toJSONString());
            if (app.getId() == null)
            {
                appInstanceMapper.insert(app);
            }
            else
            {
                appInstanceMapper.updateById(app);
            }
            syncAppDependencies(tenantId, endpointId, edgeId, instanceId, raw.getJSONArray("dependencies"));
            syncAppComponents(tenantId, endpointId, edgeId, instanceId, raw.get("properties"),
                    componentIds, componentFactories);
        }
        return instances.size();
    }

    private void syncAppDependencies(Long tenantId, Long endpointId, String edgeId, String instanceId,
                                     JSONArray dependencies)
    {
        if (dependencies == null)
        {
            return;
        }
        for (Object item : dependencies)
        {
            JSONObject dependency = objectValue(item);
            String targetId = dependency.getString("instanceId");
            String key = dependency.getString("key");
            if (StringUtils.isEmpty(targetId) || StringUtils.isEmpty(key))
            {
                continue;
            }
            EmsOpenemsAppRelation relation = appRelationMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsAppRelation>()
                    .eq(EmsOpenemsAppRelation::getTenantId, tenantId)
                    .eq(EmsOpenemsAppRelation::getEndpointId, endpointId)
                    .eq(EmsOpenemsAppRelation::getEdgeId, edgeId)
                    .eq(EmsOpenemsAppRelation::getSourceInstanceId, instanceId)
                    .eq(EmsOpenemsAppRelation::getTargetInstanceId, targetId)
                    .eq(EmsOpenemsAppRelation::getDependencyKey, key)
                    .last("limit 1"));
            if (relation == null)
            {
                relation = new EmsOpenemsAppRelation();
                relation.setTenantId(tenantId);
                relation.setEndpointId(endpointId);
                relation.setEdgeId(edgeId);
                relation.setSourceInstanceId(instanceId);
                relation.setTargetInstanceId(targetId);
                relation.setDependencyKey(key);
                relation.setDelFlag("0");
            }
            relation.setStatus("ACTIVE");
            relation.setRawJson(dependency.toJSONString());
            if (relation.getId() == null)
            {
                appRelationMapper.insert(relation);
            }
            else
            {
                appRelationMapper.updateById(relation);
            }
        }
    }

    private void syncAppComponents(Long tenantId, Long endpointId, String edgeId, String instanceId,
                                   Object properties, Set<String> componentIds, Map<String, String> componentFactories)
    {
        Map<String, String> references = new LinkedHashMap<String, String>();
        collectComponentReferences(properties, "properties", componentIds, references);
        for (Map.Entry<String, String> reference : references.entrySet())
        {
            String factory = componentFactories.get(reference.getValue());
            String role = factory != null && factory.startsWith("Bridge.") ? "BRIDGE"
                    : (factory != null && factory.startsWith("Scheduler.") ? "SCHEDULER"
                    : (factory != null && factory.startsWith("Controller.") ? "CONTROLLER" : "AUXILIARY"));
            EmsOpenemsAppComponent relation = appComponentMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsAppComponent>()
                    .eq(EmsOpenemsAppComponent::getTenantId, tenantId)
                    .eq(EmsOpenemsAppComponent::getEndpointId, endpointId)
                    .eq(EmsOpenemsAppComponent::getEdgeId, edgeId)
                    .eq(EmsOpenemsAppComponent::getAppInstanceId, instanceId)
                    .eq(EmsOpenemsAppComponent::getComponentId, reference.getValue())
                    .eq(EmsOpenemsAppComponent::getRole, role)
                    .last("limit 1"));
            if (relation == null)
            {
                relation = new EmsOpenemsAppComponent();
                relation.setTenantId(tenantId);
                relation.setEndpointId(endpointId);
                relation.setEdgeId(edgeId);
                relation.setAppInstanceId(instanceId);
                relation.setComponentId(reference.getValue());
                relation.setRole(role);
                relation.setDelFlag("0");
            }
            relation.setSource("APP_PROPERTY_REFERENCE");
            relation.setStatus("ACTIVE");
            if (relation.getId() == null)
            {
                appComponentMapper.insert(relation);
            }
            else
            {
                appComponentMapper.updateById(relation);
            }
        }
    }

    private void upsertComponentRelation(Long tenantId, Long endpointId, String edgeId, String parentId,
                                          String childId, String relationType, List<String> propertyPaths)
    {
        EmsOpenemsComponentRelation relation = componentRelationMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsComponentRelation>()
                .eq(EmsOpenemsComponentRelation::getTenantId, tenantId)
                .eq(EmsOpenemsComponentRelation::getEndpointId, endpointId)
                .eq(EmsOpenemsComponentRelation::getEdgeId, edgeId)
                .eq(EmsOpenemsComponentRelation::getParentComponentId, parentId)
                .eq(EmsOpenemsComponentRelation::getChildComponentId, childId)
                .eq(EmsOpenemsComponentRelation::getRelationType, relationType)
                .last("limit 1"));
        if (relation == null)
        {
            relation = new EmsOpenemsComponentRelation();
            relation.setTenantId(tenantId);
            relation.setEndpointId(endpointId);
            relation.setEdgeId(edgeId);
            relation.setParentComponentId(parentId);
            relation.setChildComponentId(childId);
            relation.setRelationType(relationType);
            relation.setDelFlag("0");
        }
        relation.setSource("CONFIG_REFERENCE");
        relation.setStatus("ACTIVE");
        JSONObject raw = new JSONObject();
        raw.put("propertyPaths", propertyPaths);
        relation.setRawJson(raw.toJSONString());
        if (relation.getId() == null)
        {
            componentRelationMapper.insert(relation);
        }
        else
        {
            componentRelationMapper.updateById(relation);
        }
    }

    private void collectComponentReferences(Object value, String path, Set<String> componentIds,
                                            Map<String, String> references)
    {
        if (value instanceof String)
        {
            String candidate = (String) value;
            if (componentIds.contains(candidate))
            {
                references.put(path, candidate);
            }
            return;
        }
        if (value instanceof Map)
        {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
            {
                collectComponentReferences(entry.getValue(), path + "." + entry.getKey(), componentIds, references);
            }
            return;
        }
        if (value instanceof Iterable)
        {
            int index = 0;
            for (Object item : (Iterable<?>) value)
            {
                collectComponentReferences(item, path + "[" + index++ + "]", componentIds, references);
            }
        }
    }

    private Set<String> componentIds(Map<String, Object> config)
    {
        JSONObject root = JSON.parseObject(JSON.toJSONString(config));
        JSONObject components = root == null ? null : root.getJSONObject("components");
        return components == null ? new HashSet<String>() : new HashSet<String>(components.keySet());
    }

    private Map<String, String> componentFactories(Map<String, Object> config)
    {
        Map<String, String> result = new HashMap<String, String>();
        JSONObject root = JSON.parseObject(JSON.toJSONString(config));
        JSONObject components = root == null ? null : root.getJSONObject("components");
        if (components != null)
        {
            for (Map.Entry<String, Object> entry : components.entrySet())
            {
                result.put(entry.getKey(), objectValue(entry.getValue()).getString("factoryId"));
            }
        }
        return result;
    }

    private Map<String, EmsOpenemsEdgeCreateTask> pendingTasks(Long endpointId, Long tenantId)
    {
        Map<String, EmsOpenemsEdgeCreateTask> result = new HashMap<String, EmsOpenemsEdgeCreateTask>();
        for (EmsOpenemsEdgeCreateTask task : createTaskMapper.selectList(new LambdaQueryWrapper<EmsOpenemsEdgeCreateTask>()
                .eq(EmsOpenemsEdgeCreateTask::getTenantId, tenantId)
                .eq(EmsOpenemsEdgeCreateTask::getEndpointId, endpointId)
                .eq(EmsOpenemsEdgeCreateTask::getState, "PENDING_RECONCILIATION")))
        {
            result.put(task.getCommentMarker(), task);
        }
        return result;
    }

    private void completeReconciliation(EmsOpenemsEdgeCreateTask task, String edgeId, Date now)
    {
        task.setState("SUCCESS");
        task.setBackendEdgeId(edgeId);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setFinishedAt(now);
        createTaskMapper.updateById(task);
    }

    private void markEndpointStale(EmsServerEndpoint endpoint, Long tenantId)
    {
        Date cutoff = new Date(System.currentTimeMillis() - STALE_AFTER_MILLIS);
        List<EmsOpenemsEdge> staleEdges = edgeMapper.selectList(new LambdaQueryWrapper<EmsOpenemsEdge>()
                .eq(EmsOpenemsEdge::getTenantId, tenantId)
                .eq(EmsOpenemsEdge::getEndpointId, endpoint.getId())
                .ne(EmsOpenemsEdge::getOnlineStatus, "MISSING")
                .and(wrapper -> wrapper.isNull(EmsOpenemsEdge::getLastSyncAt)
                        .or().le(EmsOpenemsEdge::getLastSyncAt, cutoff)));
        for (EmsOpenemsEdge edge : staleEdges)
        {
            edge.setOnlineStatus("STALE");
            edgeMapper.updateById(edge);
            markComponentsStaleIfExpired(tenantId, endpoint.getId(), edge.getEdgeId());
            markAppsStaleIfExpired(tenantId, endpoint.getId(), edge.getEdgeId());
        }
    }

    private void markComponentsStaleIfExpired(Long tenantId, Long endpointId, String edgeId)
    {
        Date cutoff = new Date(System.currentTimeMillis() - STALE_AFTER_MILLIS);
        Long staleCount = componentMapper.selectCount(new LambdaQueryWrapper<EmsOpenemsComponent>()
                .eq(EmsOpenemsComponent::getTenantId, tenantId)
                .eq(EmsOpenemsComponent::getEndpointId, endpointId)
                .eq(EmsOpenemsComponent::getEdgeId, edgeId)
                .ne(EmsOpenemsComponent::getStatus, "MISSING")
                .and(wrapper -> wrapper.isNull(EmsOpenemsComponent::getLastSeenAt)
                        .or().le(EmsOpenemsComponent::getLastSeenAt, cutoff)));
        if (staleCount == null || staleCount <= 0)
        {
            return;
        }
        componentMapper.update(null, new LambdaUpdateWrapper<EmsOpenemsComponent>()
                .set(EmsOpenemsComponent::getStatus, "STALE")
                .eq(EmsOpenemsComponent::getTenantId, tenantId)
                .eq(EmsOpenemsComponent::getEndpointId, endpointId)
                .eq(EmsOpenemsComponent::getEdgeId, edgeId)
                .ne(EmsOpenemsComponent::getStatus, "MISSING")
                .and(wrapper -> wrapper.isNull(EmsOpenemsComponent::getLastSeenAt)
                        .or().le(EmsOpenemsComponent::getLastSeenAt, cutoff)));
        componentRelationMapper.update(null, new LambdaUpdateWrapper<EmsOpenemsComponentRelation>()
                .set(EmsOpenemsComponentRelation::getStatus, "STALE")
                .eq(EmsOpenemsComponentRelation::getTenantId, tenantId)
                .eq(EmsOpenemsComponentRelation::getEndpointId, endpointId)
                .eq(EmsOpenemsComponentRelation::getEdgeId, edgeId)
                .ne(EmsOpenemsComponentRelation::getStatus, "MISSING"));
    }

    private void markAppsStaleIfExpired(Long tenantId, Long endpointId, String edgeId)
    {
        Date cutoff = new Date(System.currentTimeMillis() - STALE_AFTER_MILLIS);
        Long staleCount = appInstanceMapper.selectCount(new LambdaQueryWrapper<EmsOpenemsAppInstance>()
                .eq(EmsOpenemsAppInstance::getTenantId, tenantId)
                .eq(EmsOpenemsAppInstance::getEndpointId, endpointId)
                .eq(EmsOpenemsAppInstance::getEdgeId, edgeId)
                .ne(EmsOpenemsAppInstance::getStatus, "MISSING")
                .and(wrapper -> wrapper.isNull(EmsOpenemsAppInstance::getLastSeenAt)
                        .or().le(EmsOpenemsAppInstance::getLastSeenAt, cutoff)));
        if (staleCount == null || staleCount <= 0)
        {
            return;
        }
        appInstanceMapper.update(null, new LambdaUpdateWrapper<EmsOpenemsAppInstance>()
                .set(EmsOpenemsAppInstance::getStatus, "STALE")
                .eq(EmsOpenemsAppInstance::getTenantId, tenantId)
                .eq(EmsOpenemsAppInstance::getEndpointId, endpointId)
                .eq(EmsOpenemsAppInstance::getEdgeId, edgeId)
                .ne(EmsOpenemsAppInstance::getStatus, "MISSING")
                .and(wrapper -> wrapper.isNull(EmsOpenemsAppInstance::getLastSeenAt)
                        .or().le(EmsOpenemsAppInstance::getLastSeenAt, cutoff)));
        appComponentMapper.update(null, new LambdaUpdateWrapper<EmsOpenemsAppComponent>()
                .set(EmsOpenemsAppComponent::getStatus, "STALE")
                .eq(EmsOpenemsAppComponent::getTenantId, tenantId)
                .eq(EmsOpenemsAppComponent::getEndpointId, endpointId)
                .eq(EmsOpenemsAppComponent::getEdgeId, edgeId)
                .ne(EmsOpenemsAppComponent::getStatus, "MISSING"));
        appRelationMapper.update(null, new LambdaUpdateWrapper<EmsOpenemsAppRelation>()
                .set(EmsOpenemsAppRelation::getStatus, "STALE")
                .eq(EmsOpenemsAppRelation::getTenantId, tenantId)
                .eq(EmsOpenemsAppRelation::getEndpointId, endpointId)
                .eq(EmsOpenemsAppRelation::getEdgeId, edgeId)
                .ne(EmsOpenemsAppRelation::getStatus, "MISSING"));
    }

    private void markEdgeResourcesMissing(Long tenantId, Long endpointId, String edgeId)
    {
        markComponentsMissing(tenantId, endpointId, edgeId);
        markAppsMissing(tenantId, endpointId, edgeId);
    }

    private void markComponentsMissing(Long tenantId, Long endpointId, String edgeId)
    {
        componentMapper.update(null, new LambdaUpdateWrapper<EmsOpenemsComponent>()
                .set(EmsOpenemsComponent::getStatus, "MISSING")
                .eq(EmsOpenemsComponent::getTenantId, tenantId)
                .eq(EmsOpenemsComponent::getEndpointId, endpointId)
                .eq(EmsOpenemsComponent::getEdgeId, edgeId));
        componentRelationMapper.update(null, new LambdaUpdateWrapper<EmsOpenemsComponentRelation>()
                .set(EmsOpenemsComponentRelation::getStatus, "MISSING")
                .eq(EmsOpenemsComponentRelation::getTenantId, tenantId)
                .eq(EmsOpenemsComponentRelation::getEndpointId, endpointId)
                .eq(EmsOpenemsComponentRelation::getEdgeId, edgeId));
    }

    private void markAppsMissing(Long tenantId, Long endpointId, String edgeId)
    {
        appInstanceMapper.update(null, new LambdaUpdateWrapper<EmsOpenemsAppInstance>()
                .set(EmsOpenemsAppInstance::getStatus, "MISSING")
                .eq(EmsOpenemsAppInstance::getTenantId, tenantId)
                .eq(EmsOpenemsAppInstance::getEndpointId, endpointId)
                .eq(EmsOpenemsAppInstance::getEdgeId, edgeId));
        appComponentMapper.update(null, new LambdaUpdateWrapper<EmsOpenemsAppComponent>()
                .set(EmsOpenemsAppComponent::getStatus, "MISSING")
                .eq(EmsOpenemsAppComponent::getTenantId, tenantId)
                .eq(EmsOpenemsAppComponent::getEndpointId, endpointId)
                .eq(EmsOpenemsAppComponent::getEdgeId, edgeId));
        appRelationMapper.update(null, new LambdaUpdateWrapper<EmsOpenemsAppRelation>()
                .set(EmsOpenemsAppRelation::getStatus, "MISSING")
                .eq(EmsOpenemsAppRelation::getTenantId, tenantId)
                .eq(EmsOpenemsAppRelation::getEndpointId, endpointId)
                .eq(EmsOpenemsAppRelation::getEdgeId, edgeId));
    }

    private Date parseDate(Object value)
    {
        if (value == null || StringUtils.isEmpty(String.valueOf(value)))
        {
            return null;
        }
        String text = String.valueOf(value);
        try
        {
            return Date.from(Instant.parse(text));
        }
        catch (DateTimeParseException ignored)
        {
            try
            {
                return Date.from(OffsetDateTime.parse(text).toInstant());
            }
            catch (DateTimeParseException ignoredAgain)
            {
                try
                {
                    return Date.from(ZonedDateTime.parse(text).toInstant());
                }
                catch (DateTimeParseException ignoredLast)
                {
                    return null;
                }
            }
        }
    }

    private String sha256(String value)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest)
            {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }

    private JSONObject objectValue(Object value)
    {
        if (value instanceof JSONObject)
        {
            return (JSONObject) value;
        }
        return JSON.parseObject(JSON.toJSONString(value));
    }

    private String jsonString(Object value)
    {
        return value == null ? null : JSON.toJSONString(value);
    }

    private String stringValue(Object value)
    {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean booleanValue(Object value, boolean defaultValue)
    {
        if (value == null)
        {
            return defaultValue;
        }
        if (value instanceof Boolean)
        {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static class EdgeSyncResult
    {
        private final int affected;
        private final List<Map<String, Object>> newlyOnlineRows;

        private EdgeSyncResult(int affected, List<Map<String, Object>> newlyOnlineRows)
        {
            this.affected = affected;
            this.newlyOnlineRows = newlyOnlineRows;
        }
    }
}
