package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.domain.entity.EmsCompany;
import com.witos.ems.server.domain.entity.EmsDevice;
import com.witos.ems.server.domain.entity.EmsDeviceBindingHistory;
import com.witos.ems.server.domain.entity.EmsDeviceComponent;
import com.witos.ems.server.domain.entity.EmsDeviceHierarchy;
import com.witos.ems.server.domain.entity.EmsStation;
import com.witos.ems.server.mapper.EmsDeviceBindingHistoryMapper;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsDeviceComponentMapper;
import com.witos.ems.server.mapper.EmsDeviceHierarchyMapper;
import com.witos.ems.server.mapper.EmsDeviceMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.ems.server.openems.OpenemsBindTokenCodec;
import com.witos.ems.server.openems.OpenemsComponentCandidate;
import com.witos.ems.server.openems.OpenemsDeviceDiscoveryService;
import com.witos.ems.server.service.EmsDeviceService;
import com.witos.ems.server.support.EmsPageSupport;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class EmsDeviceServiceImpl implements EmsDeviceService
{
    private static final long DEFAULT_ENDPOINT_ID = 1L;
    private static final String DEVICE_TYPE_CONTROLLER = "CONTROLLER";

    @Resource
    private EmsDeviceMapper deviceMapper;

    @Resource
    private EmsDeviceComponentMapper deviceComponentMapper;

    @Resource
    private EmsDeviceBindingHistoryMapper deviceBindingHistoryMapper;

    @Resource
    private EmsDeviceHierarchyMapper deviceHierarchyMapper;

    @Resource
    private EmsCompanyMapper companyMapper;

    @Resource
    private EmsStationMapper stationMapper;

    @Resource
    private EmsAuthScopeService authScopeService;

    @Resource
    private OpenemsDeviceDiscoveryService openemsDeviceDiscoveryService;

    @Override
    public IPage<Map<String, Object>> list(Map<String, String> query, String fixedDeviceType)
    {
        Map<String, Object> params = queryMap(query);
        if (StringUtils.isNotEmpty(fixedDeviceType))
        {
            params.put("deviceType", fixedDeviceType);
        }
        return deviceMapper.selectDevicePage(EmsPageSupport.page(), params, authScopeService.currentScope());
    }

    @Override
    public List<Map<String, Object>> listAll(Map<String, String> query, String fixedDeviceType)
    {
        Map<String, Object> params = queryMap(query);
        if (StringUtils.isNotEmpty(fixedDeviceType))
        {
            params.put("deviceType", fixedDeviceType);
        }
        return deviceMapper.selectDeviceList(params, authScopeService.currentScope());
    }

    @Override
    public Map<String, Object> get(Long id)
    {
        return deviceMapper.selectDeviceDetail(id, authScopeService.currentScope());
    }

    @Override
    public List<Map<String, Object>> bindCandidates(Map<String, String> query)
    {
        Map<String, Object> params = queryMap(query);
        String serialNo = EmsRequestSupport.stringValue(params.get("serialNo"));
        if (StringUtils.isEmpty(serialNo))
        {
            throw new ServiceException("设备SN不能为空");
        }
        Long serverEndpointId = EmsRequestSupport.asLong(params.get("serverEndpointId"));
        if (serverEndpointId == null)
        {
            throw new ServiceException("请选择OpenEMS服务端点");
        }
        String targetDeviceType = EmsRequestSupport.stringValue(params.get("deviceType"));
        Long tenantId = resolveCandidateTenantId(params);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (OpenemsComponentCandidate candidate : openemsDeviceDiscoveryService.findComponentsBySerialNo(serverEndpointId, serialNo))
        {
            Map<String, Object> row = candidate.toMap();
            EmsDeviceComponent activeSnBinding = findActiveBindingBySerialNo(tenantId, candidate.getSerialNo(), null);
            EmsDeviceComponent activeComponentBinding = findActiveBindingByOpenems(tenantId, candidate, null);
            boolean bindableComponent = isBindableComponent(candidate, targetDeviceType);
            row.put("available", bindableComponent && activeSnBinding == null && activeComponentBinding == null);
            if (!bindableComponent)
            {
                row.put("unavailableReason", edgeBinding(candidate)
                        ? "Edge控制器SN只能在控制器管理中绑定"
                        : "通信设备SN仅用于识别来源，不能直接绑定为业务设备");
            }
            row.put("boundDeviceId", activeSnBinding != null ? activeSnBinding.getDeviceId()
                    : activeComponentBinding == null ? null : activeComponentBinding.getDeviceId());
            rows.add(row);
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Map<String, Object> body, String fixedDeviceType)
    {
        Long deviceId = EmsRequestSupport.coalesceId(body, "deviceId", "id");
        Long companyId = EmsRequestSupport.requiredLong(body, "companyId", "公司不能为空");
        Long stationId = EmsRequestSupport.requiredLong(body, "stationId", "电站不能为空");
        String deviceName = EmsRequestSupport.stringValue(body.get("deviceName"));
        if (StringUtils.isEmpty(deviceName))
        {
            throw new ServiceException("设备名称不能为空");
        }
        EmsCompany company = validateCompany(companyId);
        EmsStation station = validateStation(companyId, stationId);
        if (!Objects.equals(company.getTenantId(), station.getTenantId()))
        {
            throw new ServiceException("设备所属公司与电站不属于同一租户");
        }
        EmsDevice current = deviceId == null ? null : requireDevice(deviceId);
        Long tenantId = current == null ? station.getTenantId() : current.getTenantId();
        if (!Objects.equals(tenantId, station.getTenantId()))
        {
            throw new ServiceException("不能将设备迁移到其他租户");
        }
        validateController(deviceId, tenantId, companyId, stationId, EmsRequestSupport.asLong(body.get("controllerId")));
        String targetDeviceType = StringUtils.isNotEmpty(fixedDeviceType)
                ? fixedDeviceType : EmsRequestSupport.defaultString(body.get("deviceType"), "OTHER");

        EmsDevice device = new EmsDevice();
        device.setId(deviceId);
        device.setTenantId(tenantId);
        device.setCompanyId(companyId);
        device.setStationId(stationId);
        device.setControllerId(EmsRequestSupport.asLong(body.get("controllerId")));
        device.setDeviceCode(EmsRequestSupport.stringValue(body.get("deviceCode")));
        device.setDeviceName(deviceName);
        device.setDeviceType(targetDeviceType);
        device.setRatedCapacity(EmsRequestSupport.asBigDecimal(body.get("ratedCapacity")));
        device.setModel(EmsRequestSupport.stringValue(body.get("model")));
        device.setSerialNo(EmsRequestSupport.stringValue(body.get("serialNo")));
        device.setManufacturer(EmsRequestSupport.stringValue(body.get("manufacturer")));
        device.setFirmwareVersion(EmsRequestSupport.stringValue(body.get("firmwareVersion")));
        if (body.containsKey("lastHeartbeatTime"))
        {
            device.setLastHeartbeatTime(EmsRequestSupport.nullableTimestamp(body.get("lastHeartbeatTime")));
        }
        device.setControllerVersion(EmsRequestSupport.stringValue(body.get("controllerVersion")));
        if (body.containsKey("installDate"))
        {
            device.setInstallDate(EmsRequestSupport.nullableTimestamp(body.get("installDate")));
        }
        device.setCommStatus(EmsRequestSupport.defaultString(body.get("commStatus"), "UNKNOWN"));
        device.setStatus(EmsRequestSupport.defaultString(body.get("status"), "NORMAL"));
        device.setRemark(EmsRequestSupport.stringValue(body.get("remark")));

        if (deviceId == null)
        {
            deviceMapper.insert(device);
            deviceId = device.getId();
        }
        else
        {
            deviceMapper.updateById(device);
        }

        saveDeviceComponentBinding(tenantId, deviceId, companyId, stationId, body, targetDeviceType);
        return get(deviceId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> unbind(Long id)
    {
        EmsDevice device = requireDevice(id);
        unbindDevice(device.getTenantId(), id);
        return get(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean remove(Long id)
    {
        EmsDevice device = requireDevice(id);
        unbindDevice(device.getTenantId(), id);
        return deviceMapper.deleteById(id) > 0;
    }

    private void saveDeviceComponentBinding(Long tenantId, Long deviceId, Long companyId, Long stationId,
                                            Map<String, Object> body,
                                            String targetDeviceType)
    {
        String bindToken = EmsRequestSupport.stringValue(body.get("bindToken"));
        if (StringUtils.isEmpty(bindToken))
        {
            if ("true".equalsIgnoreCase(EmsRequestSupport.stringValue(body.get("clearComponent"))))
            {
                unbindDevice(tenantId, deviceId);
            }
            return;
        }

        OpenemsComponentCandidate candidate = resolveVerifiedCandidate(OpenemsBindTokenCodec.decode(bindToken), targetDeviceType);
        if (StringUtils.isEmpty(candidate.getEdgeId()) || StringUtils.isEmpty(candidate.getComponentId()))
        {
            throw new ServiceException("OpenEMS组件信息不完整");
        }
        if (StringUtils.isEmpty(candidate.getSerialNo()))
        {
            throw new ServiceException("OpenEMS组件缺少SN，不能绑定");
        }
        if (candidate.getServerEndpointId() == null)
        {
            throw new ServiceException("OpenEMS组件缺少服务端点，请重新选择端点并查询SN");
        }
        validateBindingUniqueness(tenantId, deviceId, candidate);

        EmsDeviceComponent active = findActiveBindingByDevice(tenantId, deviceId);
        if (active != null)
        {
            if (sameOpenemsComponent(active, candidate))
            {
                return;
            }
            unbindDevice(tenantId, deviceId);
        }

        Date now = new Date();
        EmsDeviceComponent component = new EmsDeviceComponent();
        component.setTenantId(tenantId);
        component.setCompanyId(companyId);
        component.setStationId(stationId);
        component.setDeviceId(deviceId);
        component.setServerEndpointId(candidate.getServerEndpointId());
        component.setEdgeId(candidate.getEdgeId());
        component.setComponentId(candidate.getComponentId());
        component.setComponentType(candidate.getComponentType());
        component.setComponentAlias(candidate.getComponentAlias());
        component.setSerialNo(candidate.getSerialNo());
        component.setParentEdgeId(candidate.getParentEdgeId());
        component.setParentComponentId(candidate.getParentComponentId());
        component.setBindTime(now);
        component.setBindStatus("ACTIVE");
        component.setBindSource("SN_LOOKUP");
        component.setEnabled("0");
        component.setCreateBy(EmsRequestSupport.currentUsername());
        component.setCreateTime(now);
        deviceComponentMapper.insert(component);
        insertBindingHistory(component, now);
        saveDeviceHierarchy(component, now);
    }

    private OpenemsComponentCandidate resolveVerifiedCandidate(OpenemsComponentCandidate candidate, String targetDeviceType)
    {
        if (!isBindableComponent(candidate, targetDeviceType))
        {
            throw new ServiceException(edgeBinding(candidate)
                    ? "Edge控制器SN只能绑定到控制器"
                    : "通信设备SN不能直接绑定为业务设备，请选择具体OpenEMS组件");
        }
        Long serverEndpointId = candidate.getServerEndpointId();
        if (serverEndpointId == null)
        {
            throw new ServiceException("OpenEMS组件缺少服务端点，请重新查询SN");
        }
        for (OpenemsComponentCandidate current : openemsDeviceDiscoveryService.findComponentsBySerialNo(serverEndpointId, candidate.getSerialNo()))
        {
            if (sameCandidate(candidate, current))
            {
                return current;
            }
        }
        throw new ServiceException("OpenEMS组件绑定凭证已失效，请重新查询SN后选择组件");
    }

    private boolean isBindableComponent(OpenemsComponentCandidate candidate, String targetDeviceType)
    {
        if (candidate == null)
        {
            return false;
        }
        if (edgeBinding(candidate))
        {
            return DEVICE_TYPE_CONTROLLER.equalsIgnoreCase(targetDeviceType);
        }
        return true;
    }

    private boolean edgeBinding(OpenemsComponentCandidate candidate)
    {
        return candidate != null
                && ("_edge".equalsIgnoreCase(candidate.getComponentId())
                || "EDGE".equalsIgnoreCase(candidate.getComponentType()));
    }

    private boolean sameCandidate(OpenemsComponentCandidate expected, OpenemsComponentCandidate actual)
    {
        return expected.getServerEndpointId() != null
            && expected.getServerEndpointId().equals(actual.getServerEndpointId())
                && sameText(expected.getEdgeId(), actual.getEdgeId())
                && sameText(expected.getComponentId(), actual.getComponentId())
                && sameText(expected.getSerialNo(), actual.getSerialNo());
    }

    private boolean sameText(String left, String right)
    {
        return StringUtils.isNotEmpty(left) && StringUtils.isNotEmpty(right) && left.equalsIgnoreCase(right);
    }

    private void unbindDevice(Long tenantId, Long deviceId)
    {
        EmsDeviceComponent component = findActiveBindingByDevice(tenantId, deviceId);
        if (component == null)
        {
            return;
        }
        Date now = new Date();
        component.setBindStatus("UNBOUND");
        component.setUnbindTime(now);
        component.setEnabled("1");
        component.setUpdateBy(EmsRequestSupport.currentUsername());
        component.setUpdateTime(now);
        deviceComponentMapper.updateById(component);

        EmsDeviceBindingHistory history = deviceBindingHistoryMapper.selectOne(new LambdaQueryWrapper<EmsDeviceBindingHistory>()
                .eq(EmsDeviceBindingHistory::getTenantId, component.getTenantId())
                .eq(EmsDeviceBindingHistory::getDeviceComponentId, component.getId())
                .eq(EmsDeviceBindingHistory::getBindStatus, "ACTIVE")
                .last("limit 1"));
        if (history != null)
        {
            history.setBindStatus("UNBOUND");
            history.setUnbindTime(now);
            history.setUnbindBy(EmsRequestSupport.currentUsername());
            history.setUpdateTime(now);
            deviceBindingHistoryMapper.updateById(history);
        }
        disableDeviceHierarchy(component, now);
    }

    private void saveDeviceHierarchy(EmsDeviceComponent component, Date now)
    {
        disableDeviceHierarchy(component, now);
        EmsDeviceComponent parentComponent = findParentActiveBinding(component);
        EmsDeviceHierarchy hierarchy = new EmsDeviceHierarchy();
        hierarchy.setTenantId(component.getTenantId());
        hierarchy.setCompanyId(component.getCompanyId());
        hierarchy.setStationId(component.getStationId());
        hierarchy.setDeviceId(component.getDeviceId());
        hierarchy.setParentDeviceId(parentComponent == null ? 0L : parentComponent.getDeviceId());
        hierarchy.setDeviceComponentId(component.getId());
        hierarchy.setParentDeviceComponentId(parentComponent == null ? null : parentComponent.getId());
        hierarchy.setRelationType("CONTAINS");
        hierarchy.setLevelNo(parentComponent == null ? 1 : 2);
        hierarchy.setPath(parentComponent == null
                ? "/" + component.getDeviceId() + "/"
                : "/" + parentComponent.getDeviceId() + "/" + component.getDeviceId() + "/");
        hierarchy.setEnabled("0");
        hierarchy.setDelFlag("0");
        hierarchy.setCreateBy(EmsRequestSupport.currentUsername());
        hierarchy.setCreateTime(now);
        deviceHierarchyMapper.insert(hierarchy);
    }

    private void disableDeviceHierarchy(EmsDeviceComponent component, Date now)
    {
        EmsDeviceHierarchy hierarchy = deviceHierarchyMapper.selectOne(new LambdaQueryWrapper<EmsDeviceHierarchy>()
                .eq(EmsDeviceHierarchy::getTenantId, component.getTenantId())
                .eq(EmsDeviceHierarchy::getDeviceId, component.getDeviceId())
                .eq(EmsDeviceHierarchy::getEnabled, "0")
                .eq(EmsDeviceHierarchy::getDelFlag, "0")
                .last("limit 1"));
        if (hierarchy == null)
        {
            return;
        }
        hierarchy.setEnabled("1");
        hierarchy.setUpdateBy(EmsRequestSupport.currentUsername());
        hierarchy.setUpdateTime(now);
        deviceHierarchyMapper.updateById(hierarchy);
    }

    private EmsDeviceComponent findParentActiveBinding(EmsDeviceComponent component)
    {
        if (StringUtils.isEmpty(component.getParentComponentId()))
        {
            return null;
        }
        return deviceComponentMapper.selectOne(new LambdaQueryWrapper<EmsDeviceComponent>()
                .eq(EmsDeviceComponent::getTenantId, component.getTenantId())
                .eq(EmsDeviceComponent::getStationId, component.getStationId())
                .eq(EmsDeviceComponent::getServerEndpointId, component.getServerEndpointId())
                .eq(EmsDeviceComponent::getEdgeId, StringUtils.isEmpty(component.getParentEdgeId()) ? component.getEdgeId() : component.getParentEdgeId())
                .eq(EmsDeviceComponent::getComponentId, component.getParentComponentId())
                .eq(EmsDeviceComponent::getBindStatus, "ACTIVE")
                .eq(EmsDeviceComponent::getDelFlag, "0")
                .last("limit 1"));
    }

    private void validateBindingUniqueness(Long tenantId, Long deviceId, OpenemsComponentCandidate candidate)
    {
        EmsDeviceComponent activeSnBinding = findActiveBindingBySerialNo(tenantId, candidate.getSerialNo(), deviceId);
        if (activeSnBinding != null)
        {
            throw new ServiceException("该SN已绑定其他EMS设备");
        }
        EmsDeviceComponent activeComponentBinding = findActiveBindingByOpenems(tenantId, candidate, deviceId);
        if (activeComponentBinding != null)
        {
            throw new ServiceException("该OpenEMS组件已绑定其他EMS设备");
        }
    }

    private EmsDeviceComponent findActiveBindingByDevice(Long tenantId, Long deviceId)
    {
        return deviceComponentMapper.selectOne(new LambdaQueryWrapper<EmsDeviceComponent>()
                .eq(EmsDeviceComponent::getTenantId, tenantId)
                .eq(EmsDeviceComponent::getDeviceId, deviceId)
                .eq(EmsDeviceComponent::getBindStatus, "ACTIVE")
                .eq(EmsDeviceComponent::getDelFlag, "0")
                .last("limit 1"));
    }

    private EmsDeviceComponent findActiveBindingBySerialNo(Long tenantId, String serialNo, Long excludedDeviceId)
    {
        if (StringUtils.isEmpty(serialNo))
        {
            return null;
        }
        LambdaQueryWrapper<EmsDeviceComponent> wrapper = new LambdaQueryWrapper<EmsDeviceComponent>()
                .eq(EmsDeviceComponent::getTenantId, tenantId)
                .eq(EmsDeviceComponent::getSerialNo, serialNo)
                .eq(EmsDeviceComponent::getBindStatus, "ACTIVE")
                .eq(EmsDeviceComponent::getDelFlag, "0");
        if (excludedDeviceId != null)
        {
            wrapper.ne(EmsDeviceComponent::getDeviceId, excludedDeviceId);
        }
        return deviceComponentMapper.selectOne(wrapper.last("limit 1"));
    }

    private EmsDeviceComponent findActiveBindingByOpenems(Long tenantId, OpenemsComponentCandidate candidate,
                                                          Long excludedDeviceId)
    {
        LambdaQueryWrapper<EmsDeviceComponent> wrapper = new LambdaQueryWrapper<EmsDeviceComponent>()
                .eq(EmsDeviceComponent::getTenantId, tenantId)
                .eq(EmsDeviceComponent::getServerEndpointId, candidate.getServerEndpointId() == null ? DEFAULT_ENDPOINT_ID : candidate.getServerEndpointId())
                .eq(EmsDeviceComponent::getEdgeId, candidate.getEdgeId())
                .eq(EmsDeviceComponent::getComponentId, candidate.getComponentId())
                .eq(EmsDeviceComponent::getBindStatus, "ACTIVE")
                .eq(EmsDeviceComponent::getDelFlag, "0");
        if (excludedDeviceId != null)
        {
            wrapper.ne(EmsDeviceComponent::getDeviceId, excludedDeviceId);
        }
        return deviceComponentMapper.selectOne(wrapper.last("limit 1"));
    }

    private boolean sameOpenemsComponent(EmsDeviceComponent component, OpenemsComponentCandidate candidate)
    {
        Long serverEndpointId = candidate.getServerEndpointId() == null ? DEFAULT_ENDPOINT_ID : candidate.getServerEndpointId();
        return serverEndpointId.equals(component.getServerEndpointId())
                && candidate.getEdgeId().equals(component.getEdgeId())
                && candidate.getComponentId().equals(component.getComponentId());
    }

    private void insertBindingHistory(EmsDeviceComponent component, Date bindTime)
    {
        EmsDeviceBindingHistory history = new EmsDeviceBindingHistory();
        history.setTenantId(component.getTenantId());
        history.setCompanyId(component.getCompanyId());
        history.setStationId(component.getStationId());
        history.setDeviceId(component.getDeviceId());
        history.setDeviceComponentId(component.getId());
        history.setServerEndpointId(component.getServerEndpointId());
        history.setEdgeId(component.getEdgeId());
        history.setComponentId(component.getComponentId());
        history.setSerialNo(component.getSerialNo());
        history.setParentEdgeId(component.getParentEdgeId());
        history.setParentComponentId(component.getParentComponentId());
        history.setComponentType(component.getComponentType());
        history.setComponentAlias(component.getComponentAlias());
        history.setBindTime(bindTime);
        history.setBindStatus("ACTIVE");
        history.setBindBy(EmsRequestSupport.currentUsername());
        history.setCreateTime(bindTime);
        deviceBindingHistoryMapper.insert(history);
    }

    private Map<String, Object> queryMap(Map<String, String> query)
    {
        return query == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(query);
    }

    private EmsCompany validateCompany(Long companyId)
    {
        Map<String, Object> companyDetail = companyMapper.selectCompanyDetail(companyId, authScopeService.currentScope());
        if (companyDetail == null || companyDetail.isEmpty())
        {
            throw new ServiceException("公司超出当前授权范围");
        }
        EmsCompany company = companyMapper.selectById(companyId);
        if (company == null)
        {
            throw new ServiceException("公司不存在");
        }
        return company;
    }

    private EmsStation validateStation(Long companyId, Long stationId)
    {
        Map<String, Object> stationDetail = stationMapper.selectStationDetail(stationId, authScopeService.currentScope());
        if (stationDetail == null || stationDetail.isEmpty())
        {
            throw new ServiceException("电站超出当前授权范围");
        }
        Long stationCompanyId = EmsRequestSupport.asLong(stationDetail.get("companyId"));
        if (stationCompanyId != null && !stationCompanyId.equals(companyId))
        {
            throw new ServiceException("设备所属电站与公司不匹配");
        }
        EmsStation station = stationMapper.selectById(stationId);
        if (station == null)
        {
            throw new ServiceException("电站不存在");
        }
        return station;
    }

    private void validateController(Long deviceId, Long tenantId, Long companyId, Long stationId, Long controllerId)
    {
        if (controllerId == null)
        {
            return;
        }
        if (deviceId != null && deviceId.equals(controllerId))
        {
            throw new ServiceException("所属控制器不能选择当前设备");
        }
        EmsDevice controller = deviceMapper.selectById(controllerId);
        if (controller == null || !"0".equals(controller.getDelFlag()))
        {
            throw new ServiceException("所属控制器不存在");
        }
        if (!"CONTROLLER".equalsIgnoreCase(controller.getDeviceType()))
        {
            throw new ServiceException("所属控制器类型不正确");
        }
        if (!Objects.equals(tenantId, controller.getTenantId()))
        {
            throw new ServiceException("所属控制器与设备不属于同一租户");
        }
        if (controller.getCompanyId() != null && !controller.getCompanyId().equals(companyId))
        {
            throw new ServiceException("所属控制器与公司不匹配");
        }
        if (controller.getStationId() != null && !controller.getStationId().equals(stationId))
        {
            throw new ServiceException("所属控制器与电站不匹配");
        }
    }

    private EmsDevice requireDevice(Long deviceId)
    {
        Map<String, Object> detail = deviceMapper.selectDeviceDetail(deviceId, authScopeService.currentScope());
        if (detail == null || detail.isEmpty())
        {
            throw new ServiceException("设备不存在或超出当前授权范围");
        }
        EmsDevice device = deviceMapper.selectById(deviceId);
        if (device == null || !"0".equals(device.getDelFlag()))
        {
            throw new ServiceException("设备不存在");
        }
        return device;
    }

    private Long resolveCandidateTenantId(Map<String, Object> params)
    {
        Long stationId = EmsRequestSupport.asLong(params.get("stationId"));
        if (stationId == null)
        {
            return EmsRequestSupport.requestedTenantId(params);
        }
        Map<String, Object> detail = stationMapper.selectStationDetail(stationId, authScopeService.currentScope());
        if (detail == null || detail.isEmpty())
        {
            throw new ServiceException("电站不存在或超出当前授权范围");
        }
        return EmsRequestSupport.asLong(detail.get("tenantId"));
    }
}
