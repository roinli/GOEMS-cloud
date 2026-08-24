package com.witos.ems.server.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.auth.EmsDataScope;
import com.witos.ems.server.domain.entity.EmsCompany;
import com.witos.ems.server.domain.entity.EmsOpenemsBinding;
import com.witos.ems.server.domain.entity.EmsOpenemsBindingHistory;
import com.witos.ems.server.domain.entity.EmsOpenemsBackfillTask;
import com.witos.ems.server.domain.entity.EmsOpenemsDevice;
import com.witos.ems.server.domain.entity.EmsOpenemsEdge;
import com.witos.ems.server.domain.entity.EmsOpenemsProvisionTask;
import com.witos.ems.server.domain.entity.EmsStation;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsOpenemsBindingHistoryMapper;
import com.witos.ems.server.mapper.EmsOpenemsBindingMapper;
import com.witos.ems.server.mapper.EmsOpenemsBackfillTaskMapper;
import com.witos.ems.server.mapper.EmsOpenemsDeviceMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeMapper;
import com.witos.ems.server.mapper.EmsOpenemsProvisionTaskMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.ems.server.service.EmsOpenemsBindingService;
import com.witos.ems.server.service.EmsOpenemsBusinessProjectionService;
import com.witos.ems.server.support.EmsPageSupport;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EmsOpenemsBindingServiceImpl implements EmsOpenemsBindingService
{
    @Resource
    private EmsOpenemsEdgeMapper edgeMapper;

    @Resource
    private EmsAuthScopeService authScopeService;

    @Resource
    private EmsOpenemsDeviceMapper deviceMapper;

    @Resource
    private EmsOpenemsBindingMapper bindingMapper;

    @Resource
    private EmsOpenemsBindingHistoryMapper historyMapper;

    @Resource
    private EmsCompanyMapper companyMapper;

    @Resource
    private EmsStationMapper stationMapper;

    @Resource
    private EmsOpenemsProvisionTaskMapper provisionTaskMapper;

    @Resource
    private EmsOpenemsBackfillTaskMapper backfillTaskMapper;

    @Resource
    private EmsOpenemsBusinessProjectionService businessProjectionService;

    @Override
    public IPage<Map<String, Object>> listDevices(Map<String, String> query)
    {
        query = query == null ? new LinkedHashMap<String, String>() : query;
        Long tenantId = EmsRequestSupport.currentTenantId();
        LambdaQueryWrapper<EmsOpenemsDevice> wrapper = new LambdaQueryWrapper<EmsOpenemsDevice>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsDevice::getTenantId, tenantId)
                .eq(parseLong(query.get("endpointId")) != null, EmsOpenemsDevice::getEndpointId, parseLong(query.get("endpointId")))
                .like(StringUtils.isNotEmpty(query.get("edgeId")), EmsOpenemsDevice::getEdgeId, query.get("edgeId"))
                .like(StringUtils.isNotEmpty(query.get("deviceType")), EmsOpenemsDevice::getDeviceType, query.get("deviceType"))
                .eq(StringUtils.isNotEmpty(query.get("status")), EmsOpenemsDevice::getStatus, query.get("status"))
                .ne(StringUtils.isEmpty(query.get("status")), EmsOpenemsDevice::getStatus, "UNSUPPORTED")
                .eq(parseLong(query.get("companyId")) != null, EmsOpenemsDevice::getCompanyId, parseLong(query.get("companyId")))
                .eq(parseLong(query.get("stationId")) != null, EmsOpenemsDevice::getStationId, parseLong(query.get("stationId")))
                .orderByDesc(EmsOpenemsDevice::getId);
        applyDataScope(wrapper, authScopeService.currentScope());
        IPage<EmsOpenemsDevice> source = deviceMapper.selectPage(EmsPageSupport.<EmsOpenemsDevice>page(), wrapper);
        Page<Map<String, Object>> result = new Page<Map<String, Object>>(source.getCurrent(), source.getSize(), source.getTotal());
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (EmsOpenemsDevice device : source.getRecords())
        {
            rows.add(toDeviceView(device));
        }
        result.setRecords(rows);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> getDevice(Long id)
    {
        return toDeviceView(requireDevice(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bindEdge(Long edgeId, Map<String, Object> body)
    {
        EmsOpenemsEdge edge = requireEdge(edgeId);
        BindingRequest request = parseRequest(body, false);
        validateScope(edge.getTenantId(), request.companyId, request.stationId);
        validateOwnershipPair(edge.getCompanyId(), edge.getStationId(), "控制器");
        List<EmsOpenemsDevice> devices = devicesOf(edge);
        validateDeviceOwnership(devices, edge);
        boolean edgeWasBound = edge.getCompanyId() != null || edge.getStationId() != null;
        List<EmsOpenemsDevice> affected = new ArrayList<EmsOpenemsDevice>();
        for (EmsOpenemsDevice device : devices)
        {
            boolean deviceBound = device.getCompanyId() != null || device.getStationId() != null;
            if (deviceBound && !same(device.getCompanyId(), device.getStationId(), edge.getCompanyId(), edge.getStationId()))
            {
                throw new ServiceException("设备与控制器归属不一致，禁止继续绑定");
            }
            if (!edgeWasBound || deviceBound)
            {
                affected.add(device);
            }
        }
        BindingPlan edgePlan = evaluate(edgeContext(edge), request.companyId, request.stationId, request.effectiveFrom);
        List<BindingPlan> devicePlans = new ArrayList<BindingPlan>();
        for (EmsOpenemsDevice device : affected)
        {
            devicePlans.add(evaluate(deviceContext(device), request.companyId, request.stationId, request.effectiveFrom));
        }
        apply(edgePlan, request.source, request.reason);
        int changedCount = 0;
        for (BindingPlan plan : devicePlans)
        {
            apply(plan, "INHERITED", request.reason);
            changedCount += plan.changed ? 1 : 0;
        }
        Map<String, Object> result = bindingResult(edge, request, changedCount);
        result.put("inheritedDeviceCount", changedCount);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bindAllUnboundDevices(Long edgeId, Map<String, Object> body)
    {
        EmsOpenemsEdge edge = requireEdge(edgeId);
        validateOwnershipPair(edge.getCompanyId(), edge.getStationId(), "控制器");
        if (!bound(edge.getCompanyId(), edge.getStationId()))
        {
            throw new ServiceException("控制器尚未绑定公司和电站，不能继承设备归属");
        }
        BindingRequest request = parseRequest(body, true);
        if (request.companyId != null && !same(request.companyId, request.stationId, edge.getCompanyId(), edge.getStationId()))
        {
            throw new ServiceException("绑定公司和电站必须与控制器一致");
        }
        request.companyId = edge.getCompanyId();
        request.stationId = edge.getStationId();
        List<BindingPlan> plans = new ArrayList<BindingPlan>();
        for (EmsOpenemsDevice device : devicesOf(edge))
        {
            if (device.getCompanyId() == null && device.getStationId() == null)
            {
                plans.add(evaluate(deviceContext(device), edge.getCompanyId(), edge.getStationId(), request.effectiveFrom));
            }
            else if (!same(device.getCompanyId(), device.getStationId(), edge.getCompanyId(), edge.getStationId()))
            {
                throw new ServiceException("设备与控制器归属不一致，禁止绑定");
            }
        }
        for (BindingPlan plan : plans)
        {
            apply(plan, "INHERITED", request.reason);
        }
        int changedCount = changedCount(plans);
        Map<String, Object> result = bindingResult(edge, request, changedCount);
        result.put("boundDeviceCount", changedCount);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bindDevice(Long deviceId, Map<String, Object> body)
    {
        EmsOpenemsDevice snapshot = findDevice(deviceId);
        EmsOpenemsEdge edge = requireEdgeByKey(snapshot.getTenantId(), snapshot.getEndpointId(), snapshot.getEdgeId());
        EmsOpenemsDevice device = requireDevice(deviceId);
        ensureSameEdge(snapshot, device);
        validateOwnershipPair(edge.getCompanyId(), edge.getStationId(), "控制器");
        validateOwnershipPair(device.getCompanyId(), device.getStationId(), "设备");
        BindingRequest request = parseRequest(body, false);
        validateScope(device.getTenantId(), request.companyId, request.stationId);
        if (request.companyId != null && !same(request.companyId, request.stationId, edge.getCompanyId(), edge.getStationId()))
        {
            throw new ServiceException("设备归属必须与控制器完全一致");
        }
        if (request.companyId != null && !bound(edge.getCompanyId(), edge.getStationId()))
        {
            throw new ServiceException("控制器未绑定公司和电站，设备不能单独绑定");
        }
        BindingPlan plan = evaluate(deviceContext(device), request.companyId, request.stationId, request.effectiveFrom);
        apply(plan, request.source, request.reason);
        Map<String, Object> result = bindingResult(edge, request, plan.changed ? 1 : 0);
        result.put("deviceId", deviceId);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void inheritNewDevice(Long deviceId, Date effectiveFrom, String source)
    {
        EmsOpenemsDevice snapshot = findDevice(deviceId);
        EmsOpenemsEdge edge = requireEdgeByKey(snapshot.getTenantId(), snapshot.getEndpointId(), snapshot.getEdgeId());
        EmsOpenemsDevice device = requireDevice(deviceId);
        ensureSameEdge(snapshot, device);
        validateOwnershipPair(edge.getCompanyId(), edge.getStationId(), "控制器");
        validateOwnershipPair(device.getCompanyId(), device.getStationId(), "设备");
        if (!bound(edge.getCompanyId(), edge.getStationId()))
        {
            return;
        }
        if (device.getCompanyId() != null || device.getStationId() != null)
        {
            if (!same(device.getCompanyId(), device.getStationId(), edge.getCompanyId(), edge.getStationId()))
            {
                throw new ServiceException("新设备与控制器归属不一致");
            }
            return;
        }
        Date from = truncate(effectiveFrom == null ? new Date() : effectiveFrom);
        apply(evaluate(deviceContext(device), edge.getCompanyId(), edge.getStationId(), from),
                StringUtils.isEmpty(source) ? "INHERITED" : source, "控制器归属继承");
    }

    private BindingPlan evaluate(ResourceContext context, Long newCompanyId, Long newStationId, Date from)
    {
        Date now = truncate(new Date());
        if (from.after(now))
        {
            throw new ServiceException("生效时间不能晚于当前时间");
        }
        Long tenantId = context.tenantId;
        List<EmsOpenemsBinding> bindings = bindingMapper.selectList(new LambdaQueryWrapper<EmsOpenemsBinding>()
                .eq(EmsOpenemsBinding::getTenantId, tenantId)
                .eq(EmsOpenemsBinding::getResourceType, context.resourceType)
                .eq(EmsOpenemsBinding::getResourceId, context.resourceId)
                .orderByAsc(EmsOpenemsBinding::getEffectiveFrom)
                .last("FOR UPDATE"));
        if (bindings == null)
        {
            bindings = new ArrayList<EmsOpenemsBinding>();
        }
        bindings.sort((left, right) -> left.getEffectiveFrom().compareTo(right.getEffectiveFrom()));
        validateIntervals(bindings);
        EmsOpenemsBinding containing = null;
        for (EmsOpenemsBinding binding : bindings)
        {
            if (!binding.getEffectiveFrom().after(from)
                    && (binding.getEffectiveTo() == null || binding.getEffectiveTo().after(from)))
            {
                containing = binding;
                break;
            }
        }
        boolean oldBound = bound(context.companyId, context.stationId);
        if (oldBound && containing == null)
        {
            throw new ServiceException("资源当前归属缺少有效历史区间，拒绝覆盖");
        }
        if (oldBound && containing != null
                && !same(containing.getCompanyId(), containing.getStationId(), context.companyId, context.stationId))
        {
            throw new ServiceException("资源当前字段与绑定历史不一致");
        }
        if (!oldBound && containing != null && !same(containing.getCompanyId(), containing.getStationId(), null, null))
        {
            throw new ServiceException("资源当前字段与绑定历史不一致");
        }
        boolean newBound = bound(newCompanyId, newStationId);
        if (!newBound && containing == null)
        {
            return new BindingPlan(context, null, false, from, newCompanyId, newStationId);
        }
        if (containing != null && same(containing.getCompanyId(), containing.getStationId(), newCompanyId, newStationId))
        {
            return new BindingPlan(context, containing, false, from, newCompanyId, newStationId);
        }
        for (EmsOpenemsBinding binding : bindings)
        {
            if (binding == containing)
            {
                continue;
            }
            if (newBound && binding.getEffectiveFrom().after(from))
            {
                throw new ServiceException("新的绑定区间与已有区间重叠");
            }
        }
        if (containing != null && containing.getEffectiveFrom().equals(from) && !newBound)
        {
            throw new ServiceException("不能创建零长度解绑区间，请使用晚于原绑定开始时间的生效时间");
        }
        return new BindingPlan(context, containing, true, from, newCompanyId, newStationId);
    }

    private void apply(BindingPlan plan, String source, String reason)
    {
        if (!plan.changed)
        {
            return;
        }
        Date now = truncate(new Date());
        Long tenantId = plan.context.tenantId;
        Long oldCompanyId = plan.context.companyId;
        Long oldStationId = plan.context.stationId;
        EmsOpenemsBinding auditBinding = plan.containing;
        if (plan.containing != null && plan.containing.getEffectiveFrom().before(plan.effectiveFrom))
        {
            plan.containing.setEffectiveTo(plan.effectiveFrom);
            plan.containing.setStatus("EXPIRED");
            bindingMapper.updateById(plan.containing);
        }
        if (plan.newCompanyId != null)
        {
            EmsOpenemsBinding current = plan.containing;
            if (current == null || !current.getEffectiveFrom().equals(plan.effectiveFrom))
            {
                current = new EmsOpenemsBinding();
                current.setTenantId(tenantId);
                current.setResourceType(plan.context.resourceType);
                current.setResourceId(plan.context.resourceId);
                current.setEndpointId(plan.context.endpointId);
                current.setEdgeId(plan.context.edgeId);
                current.setComponentId(plan.context.componentId);
                current.setEffectiveFrom(plan.effectiveFrom);
                current.setCompanyId(plan.newCompanyId);
                current.setStationId(plan.newStationId);
                current.setSource(StringUtils.isEmpty(source) ? "MANUAL" : source);
                current.setStatus("ACTIVE");
                current.setDelFlag("0");
                bindingMapper.insert(current);
            }
            else
            {
                current.setCompanyId(plan.newCompanyId);
                current.setStationId(plan.newStationId);
                current.setSource(StringUtils.isEmpty(source) ? "MANUAL" : source);
                current.setEffectiveTo(null);
                current.setStatus("ACTIVE");
                current.setUpdateBy(EmsRequestSupport.currentUsername());
                bindingMapper.updateById(current);
            }
            auditBinding = current;
        }
        if (plan.context.edge != null)
        {
            EmsOpenemsEdge edge = plan.context.edge;
            edge.setCompanyId(plan.newCompanyId);
            edge.setStationId(plan.newStationId);
            edgeMapper.updateById(edge);
        }
        else
        {
            EmsOpenemsDevice device = plan.context.device;
            device.setCompanyId(plan.newCompanyId);
            device.setStationId(plan.newStationId);
            deviceMapper.updateById(device);
            if (businessProjectionService != null) businessProjectionService.syncDevice(device);
        }
        EmsOpenemsBindingHistory history = new EmsOpenemsBindingHistory();
        history.setTenantId(tenantId);
        history.setBindingId(auditBinding == null ? null : auditBinding.getId());
        history.setResourceType(plan.context.resourceType);
        history.setResourceId(plan.context.resourceId);
        history.setEndpointId(plan.context.endpointId);
        history.setEdgeId(plan.context.edgeId);
        history.setComponentId(plan.context.componentId);
        history.setOldCompanyId(oldCompanyId);
        history.setOldStationId(oldStationId);
        history.setNewCompanyId(plan.newCompanyId);
        history.setNewStationId(plan.newStationId);
        history.setEffectiveFrom(plan.effectiveFrom);
        history.setEffectiveTo(null);
        history.setOperationType(operationType(oldCompanyId, oldStationId, plan.newCompanyId, plan.newStationId, source));
        history.setOperationBy(EmsRequestSupport.currentUsername());
        history.setReason(reason);
        history.setRebuildTaskNo(plan.effectiveFrom.before(now) ? "RECALC-" + UUID.randomUUID().toString().replace("-", "") : null);
        history.setRawJson(JSON.toJSONString(historyMap(plan, source, reason)));
        historyMapper.insert(history);
    }

    private Map<String, Object> historyMap(BindingPlan plan, String source, String reason)
    {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("resourceType", plan.context.resourceType);
        map.put("resourceId", plan.context.resourceId);
        map.put("effectiveFrom", plan.effectiveFrom);
        map.put("companyId", plan.newCompanyId);
        map.put("stationId", plan.newStationId);
        map.put("source", source);
        map.put("reason", reason);
        return map;
    }

    private String operationType(Long oldCompanyId, Long oldStationId, Long newCompanyId, Long newStationId, String source)
    {
        if ("INHERITED".equalsIgnoreCase(source))
        {
            return "INHERIT";
        }
        if (!bound(oldCompanyId, oldStationId) && bound(newCompanyId, newStationId))
        {
            return "BIND";
        }
        if (bound(oldCompanyId, oldStationId) && !bound(newCompanyId, newStationId))
        {
            return "UNBIND";
        }
        return "MOVE";
    }

    private void validateIntervals(List<EmsOpenemsBinding> bindings)
    {
        if (bindings == null)
        {
            return;
        }
        EmsOpenemsBinding previous = null;
        for (EmsOpenemsBinding binding : bindings)
        {
            if (binding.getEffectiveFrom() == null
                    || (binding.getEffectiveTo() != null && !binding.getEffectiveTo().after(binding.getEffectiveFrom())))
            {
                throw new ServiceException("绑定区间边界无效");
            }
            if (previous != null && (previous.getEffectiveTo() == null || previous.getEffectiveTo().after(binding.getEffectiveFrom())))
            {
                throw new ServiceException("已有绑定区间重叠，无法继续操作");
            }
            previous = binding;
        }
    }

    private List<EmsOpenemsDevice> devicesOf(EmsOpenemsEdge edge)
    {
        List<EmsOpenemsDevice> devices = deviceMapper.selectList(new LambdaQueryWrapper<EmsOpenemsDevice>()
                .eq(EmsOpenemsDevice::getTenantId, edge.getTenantId())
                .eq(EmsOpenemsDevice::getEndpointId, edge.getEndpointId())
                .eq(EmsOpenemsDevice::getEdgeId, edge.getEdgeId())
                .eq(EmsOpenemsDevice::getDelFlag, "0")
                .last("FOR UPDATE"));
        return devices == null ? new ArrayList<EmsOpenemsDevice>() : devices;
    }

    private void validateDeviceOwnership(List<EmsOpenemsDevice> devices, EmsOpenemsEdge edge)
    {
        for (EmsOpenemsDevice device : devices)
        {
            boolean company = device.getCompanyId() != null;
            boolean station = device.getStationId() != null;
            if (company != station)
            {
                throw new ServiceException("设备公司和电站必须同时存在或同时为空");
            }
            if (bound(edge.getCompanyId(), edge.getStationId()) && company
                    && !same(device.getCompanyId(), device.getStationId(), edge.getCompanyId(), edge.getStationId()))
            {
                throw new ServiceException("设备与控制器归属不一致");
            }
        }
    }

    private EmsOpenemsEdge requireEdge(Long id)
    {
        EmsOpenemsEdge edge = edgeMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsEdge>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsEdge::getTenantId,
                        EmsRequestSupport.currentTenantId())
                .eq(EmsOpenemsEdge::getId, id)
                .eq(EmsOpenemsEdge::getDelFlag, "0")
                .last("limit 1 FOR UPDATE"));
        if (edge == null)
        {
            throw new ServiceException("OpenEMS控制器不存在或不属于当前租户");
        }
        ensureDataScope(edge.getCompanyId(), edge.getStationId(), "控制器");
        return edge;
    }

    private EmsOpenemsEdge requireEdgeByKey(Long tenantId, Long endpointId, String edgeId)
    {
        EmsOpenemsEdge edge = edgeMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsEdge>()
                .eq(EmsOpenemsEdge::getTenantId, tenantId)
                .eq(EmsOpenemsEdge::getEndpointId, endpointId)
                .eq(EmsOpenemsEdge::getEdgeId, edgeId)
                .eq(EmsOpenemsEdge::getDelFlag, "0")
                .last("limit 1 FOR UPDATE"));
        if (edge == null)
        {
            throw new ServiceException("设备所属控制器不存在或不属于当前租户");
        }
        ensureDataScope(edge.getCompanyId(), edge.getStationId(), "控制器");
        return edge;
    }

    private EmsOpenemsDevice requireDevice(Long id)
    {
        EmsOpenemsDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsDevice>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsDevice::getTenantId,
                        EmsRequestSupport.currentTenantId())
                .eq(EmsOpenemsDevice::getId, id)
                .eq(EmsOpenemsDevice::getDelFlag, "0")
                .last("limit 1 FOR UPDATE"));
        if (device == null)
        {
            throw new ServiceException("OpenEMS设备不存在或不属于当前租户");
        }
        ensureDataScope(device.getCompanyId(), device.getStationId(), "设备");
        return device;
    }

    private EmsOpenemsDevice findDevice(Long id)
    {
        EmsOpenemsDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsDevice>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsDevice::getTenantId,
                        EmsRequestSupport.currentTenantId())
                .eq(EmsOpenemsDevice::getId, id)
                .eq(EmsOpenemsDevice::getDelFlag, "0")
                .last("limit 1"));
        if (device == null)
        {
            throw new ServiceException("OpenEMS设备不存在或不属于当前租户");
        }
        ensureDataScope(device.getCompanyId(), device.getStationId(), "设备");
        return device;
    }

    private void applyDataScope(LambdaQueryWrapper<EmsOpenemsDevice> wrapper, EmsDataScope scope)
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
                item.in(EmsOpenemsDevice::getCompanyId, scope.getCompanyIds());
            }
            if (hasStations)
            {
                if (hasCompanies)
                {
                    item.or();
                }
                item.in(EmsOpenemsDevice::getStationId, scope.getStationIds());
            }
        });
    }

    private void ensureDataScope(Long companyId, Long stationId, String resourceName)
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
            throw new ServiceException(resourceName + "超出当前公司或电站授权范围");
        }
    }

    private void ensureSameEdge(EmsOpenemsDevice beforeLock, EmsOpenemsDevice afterLock)
    {
        if (!beforeLock.getEndpointId().equals(afterLock.getEndpointId()) || !beforeLock.getEdgeId().equals(afterLock.getEdgeId()))
        {
            throw new ServiceException("设备所属控制器已变化，请刷新后重试");
        }
    }

    private void validateOwnershipPair(Long companyId, Long stationId, String resourceName)
    {
        if ((companyId == null) != (stationId == null))
        {
            throw new ServiceException(resourceName + "公司和电站必须同时存在或同时为空");
        }
    }

    private void validateScope(Long tenantId, Long companyId, Long stationId)
    {
        if (companyId == null && stationId == null)
        {
            return;
        }
        if (companyId == null || stationId == null)
        {
            throw new ServiceException("公司和电站必须同时绑定或同时为空");
        }
        EmsCompany company = companyMapper.selectOne(new LambdaQueryWrapper<EmsCompany>()
                .eq(EmsCompany::getTenantId, tenantId).eq(EmsCompany::getId, companyId).eq(EmsCompany::getDelFlag, "0").last("limit 1"));
        if (company == null)
        {
            throw new ServiceException("公司不存在或不属于当前租户");
        }
        EmsStation station = stationMapper.selectOne(new LambdaQueryWrapper<EmsStation>()
                .eq(EmsStation::getTenantId, tenantId).eq(EmsStation::getId, stationId).eq(EmsStation::getDelFlag, "0").last("limit 1"));
        if (station == null)
        {
            throw new ServiceException("电站不存在或不属于当前租户");
        }
        if (!companyId.equals(station.getCompanyId()))
        {
            throw new ServiceException("电站不属于所选公司");
        }
    }

    private BindingRequest parseRequest(Map<String, Object> body, boolean allowEmpty)
    {
        if (body == null)
        {
            body = new HashMap<String, Object>();
        }
        boolean companyPresent = body.containsKey("companyId");
        boolean stationPresent = body.containsKey("stationId");
        if (!allowEmpty && (!companyPresent || !stationPresent))
        {
            throw new ServiceException("请同时传入companyId和stationId；解绑请显式传null");
        }
        Long companyId = EmsRequestSupport.asLong(body.get("companyId"));
        Long stationId = EmsRequestSupport.asLong(body.get("stationId"));
        if ((companyId == null) != (stationId == null))
        {
            throw new ServiceException("公司和电站必须同时绑定或同时为空");
        }
        Timestamp parsed = EmsRequestSupport.nullableTimestamp(body.get("effectiveFrom"));
        Date effectiveFrom = truncate(parsed == null ? new Date() : parsed);
        if (effectiveFrom.after(truncate(new Date())))
        {
            throw new ServiceException("生效时间不能晚于当前时间");
        }
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        if (reason != null && reason.length() > 512)
        {
            throw new ServiceException("变更原因不能超过512个字符");
        }
        BindingRequest request = new BindingRequest();
        request.companyId = companyId;
        request.stationId = stationId;
        request.effectiveFrom = effectiveFrom;
        request.reason = reason;
        request.source = "MANUAL";
        return request;
    }

    private Map<String, Object> bindingResult(EmsOpenemsEdge edge, BindingRequest request, int count)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("edgeId", edge.getId());
        result.put("openemsEdgeId", edge.getEdgeId());
        result.put("companyId", request.companyId);
        result.put("stationId", request.stationId);
        result.put("effectiveFrom", request.effectiveFrom);
        result.put("affectedDeviceCount", count);
        return result;
    }

    private Map<String, Object> toDeviceView(EmsOpenemsDevice device)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", device.getId());
        result.put("endpointId", device.getEndpointId());
        result.put("edgeId", device.getEdgeId());
        result.put("resourceGroupId", device.getResourceGroupId());
        result.put("primaryComponentId", device.getPrimaryComponentId());
        result.put("deviceType", device.getDeviceType());
        result.put("displayName", device.getDisplayName());
        result.put("companyId", device.getCompanyId());
        result.put("stationId", device.getStationId());
        EmsCompany company = device.getCompanyId() == null ? null : companyMapper.selectById(device.getCompanyId());
        EmsStation station = device.getStationId() == null ? null : stationMapper.selectById(device.getStationId());
        result.put("companyName", company == null ? null : company.getCompanyName());
        result.put("stationName", station == null ? null : station.getStationName());
        result.put("sourceType", device.getSourceType());
        result.put("status", device.getStatus());
        result.put("lastSeenAt", device.getLastSeenAt());
        result.put("bindingStatus", bound(device.getCompanyId(), device.getStationId()) ? "BOUND" : "UNBOUND");
        result.put("revenueEligibility", bound(device.getCompanyId(), device.getStationId()) ? "ELIGIBLE" : "NOT_APPLICABLE");
        EmsOpenemsProvisionTask provision = provisionTaskMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsProvisionTask>()
                .eq(EmsOpenemsProvisionTask::getTenantId, device.getTenantId())
                .eq(EmsOpenemsProvisionTask::getDeviceId, device.getId())
                .eq(EmsOpenemsProvisionTask::getDelFlag, "0").orderByDesc(EmsOpenemsProvisionTask::getId).last("limit 1"));
        if (provision != null)
        {
            result.put("provisionTaskId", provision.getId());
            result.put("provisionState", provision.getState());
            result.put("provisionStep", provision.getStep());
            result.put("provisionError", provision.getLastError());
        }
        EmsOpenemsBackfillTask backfill = backfillTaskMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsBackfillTask>()
                .eq(EmsOpenemsBackfillTask::getTenantId, device.getTenantId())
                .eq(EmsOpenemsBackfillTask::getDeviceId, device.getId())
                .eq(EmsOpenemsBackfillTask::getDelFlag, "0").orderByDesc(EmsOpenemsBackfillTask::getId).last("limit 1"));
        if (backfill != null)
        {
            result.put("backfillTaskId", backfill.getId());
            result.put("backfillState", backfill.getState());
            result.put("backfillProgress", backfill.getProgress());
            result.put("backfillError", backfill.getLastError());
        }
        return result;
    }

    private ResourceContext edgeContext(EmsOpenemsEdge edge)
    {
        return new ResourceContext(edge.getTenantId(), "EDGE", edge.getId(), edge.getEndpointId(), edge.getEdgeId(), null,
                edge.getCompanyId(), edge.getStationId(), edge, null);
    }

    private ResourceContext deviceContext(EmsOpenemsDevice device)
    {
        return new ResourceContext(device.getTenantId(), "DEVICE", device.getId(), device.getEndpointId(), device.getEdgeId(), device.getPrimaryComponentId(),
                device.getCompanyId(), device.getStationId(), null, device);
    }

    private int changedCount(List<BindingPlan> plans)
    {
        int count = 0;
        for (BindingPlan plan : plans)
        {
            count += plan.changed ? 1 : 0;
        }
        return count;
    }

    private boolean same(Long companyA, Long stationA, Long companyB, Long stationB)
    {
        return companyA == null && stationA == null && companyB == null && stationB == null
                || companyA != null && companyA.equals(companyB) && stationA != null && stationA.equals(stationB);
    }

    private boolean bound(Long companyId, Long stationId)
    {
        return companyId != null && stationId != null;
    }

    private Long parseLong(String value)
    {
        return StringUtils.isEmpty(value) ? null : Long.valueOf(value);
    }

    private Date truncate(Date date)
    {
        return new Date((date.getTime() / 1000L) * 1000L);
    }

    private static class BindingRequest
    {
        private Long companyId;
        private Long stationId;
        private Date effectiveFrom;
        private String source;
        private String reason;
    }

    private static class ResourceContext
    {
        private final Long tenantId;
        private final String resourceType;
        private final Long resourceId;
        private final Long endpointId;
        private final String edgeId;
        private final String componentId;
        private final Long companyId;
        private final Long stationId;
        private final EmsOpenemsEdge edge;
        private final EmsOpenemsDevice device;

        private ResourceContext(Long tenantId, String resourceType, Long resourceId, Long endpointId, String edgeId, String componentId,
                                Long companyId, Long stationId, EmsOpenemsEdge edge, EmsOpenemsDevice device)
        {
            this.tenantId = tenantId;
            this.resourceType = resourceType;
            this.resourceId = resourceId;
            this.endpointId = endpointId;
            this.edgeId = edgeId;
            this.componentId = componentId;
            this.companyId = companyId;
            this.stationId = stationId;
            this.edge = edge;
            this.device = device;
        }
    }

    private static class BindingPlan
    {
        private final ResourceContext context;
        private final EmsOpenemsBinding containing;
        private final boolean changed;
        private final Date effectiveFrom;
        private final Long newCompanyId;
        private final Long newStationId;

        private BindingPlan(ResourceContext context, EmsOpenemsBinding containing, boolean changed,
                            Date effectiveFrom, Long newCompanyId, Long newStationId)
        {
            this.context = context;
            this.containing = containing;
            this.changed = changed;
            this.effectiveFrom = effectiveFrom;
            this.newCompanyId = newCompanyId;
            this.newStationId = newStationId;
        }
    }
}
