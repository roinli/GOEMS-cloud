package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.domain.entity.EmsDevice;
import com.witos.ems.server.domain.entity.EmsDeviceComponent;
import com.witos.ems.server.domain.entity.EmsOpenemsComponent;
import com.witos.ems.server.domain.entity.EmsOpenemsDevice;
import com.witos.ems.server.mapper.EmsDeviceComponentMapper;
import com.witos.ems.server.mapper.EmsDeviceMapper;
import com.witos.ems.server.mapper.EmsOpenemsComponentMapper;
import com.witos.ems.server.mapper.EmsOpenemsDeviceMapper;
import com.witos.ems.server.service.EmsOpenemsBusinessProjectionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.Locale;

@Service
public class EmsOpenemsBusinessProjectionServiceImpl implements EmsOpenemsBusinessProjectionService
{
    @Resource private EmsDeviceMapper deviceMapper;
    @Resource private EmsDeviceComponentMapper deviceComponentMapper;
    @Resource private EmsOpenemsDeviceMapper openemsDeviceMapper;
    @Resource private EmsOpenemsComponentMapper openemsComponentMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncDevice(EmsOpenemsDevice source)
    {
        if (source == null || source.getId() == null)
        {
            return;
        }
        EmsDevice target = source.getBusinessDeviceId() == null ? null : deviceMapper.selectById(source.getBusinessDeviceId());
        if (source.getCompanyId() == null || source.getStationId() == null)
        {
            disableProjection(source, target);
            return;
        }
        if (!"ACTIVE".equals(source.getStatus()) && !"OFFLINE".equals(source.getStatus()))
        {
            disableProjection(source, target);
            return;
        }
        if (StringUtils.isEmpty(source.getPrimaryComponentId()))
        {
            return;
        }
        String factoryPid = factoryPid(source);
        String deviceCode = stableCode(source);
        if (target == null)
        {
            target = deviceMapper.selectOne(new LambdaQueryWrapper<EmsDevice>()
                    .eq(EmsDevice::getTenantId, source.getTenantId())
                    .eq(EmsDevice::getDeviceCode, deviceCode)
                    .last("limit 1"));
        }
        Date now = new Date();
        if (target == null)
        {
            target = new EmsDevice();
            target.setTenantId(source.getTenantId());
            target.setDeviceCode(deviceCode);
            target.setDelFlag("0");
            target.setCreateTime(now);
        }
        target.setCompanyId(source.getCompanyId());
        target.setStationId(source.getStationId());
        target.setControllerId(null);
        target.setDeviceName(StringUtils.isEmpty(source.getDisplayName()) ? source.getPrimaryComponentId() : source.getDisplayName());
        target.setDeviceType(businessType(source.getDeviceType(), factoryPid));
        target.setModel(factoryPid);
        target.setCommStatus("ACTIVE".equals(source.getStatus()) ? "ONLINE" : "OFFLINE");
        target.setLastHeartbeatTime(source.getLastSeenAt());
        target.setStatus("DISABLED".equals(source.getStatus()) ? "DISABLED" : "NORMAL");
        target.setUpdateTime(now);
        if (target.getId() == null) deviceMapper.insert(target); else deviceMapper.updateById(target);

        if (!target.getId().equals(source.getBusinessDeviceId()))
        {
            source.setBusinessDeviceId(target.getId());
            source.setUpdateTime(now);
            openemsDeviceMapper.updateById(source);
        }
        upsertComponent(source, target, factoryPid, now);
    }

    private void upsertComponent(EmsOpenemsDevice source, EmsDevice target, String factoryPid, Date now)
    {
        EmsDeviceComponent component = deviceComponentMapper.selectOne(new LambdaQueryWrapper<EmsDeviceComponent>()
                .eq(EmsDeviceComponent::getTenantId, source.getTenantId())
                .eq(EmsDeviceComponent::getDeviceId, target.getId())
                .eq(EmsDeviceComponent::getServerEndpointId, source.getEndpointId())
                .eq(EmsDeviceComponent::getEdgeId, source.getEdgeId())
                .eq(EmsDeviceComponent::getComponentId, source.getPrimaryComponentId())
                .last("limit 1"));
        if (component == null)
        {
            component = new EmsDeviceComponent();
            component.setTenantId(source.getTenantId());
            component.setDeviceId(target.getId());
            component.setServerEndpointId(source.getEndpointId());
            component.setEdgeId(source.getEdgeId());
            component.setComponentId(source.getPrimaryComponentId());
            component.setBindTime(now);
            component.setBindSource("OPENEMS_PROJECTION");
            component.setDelFlag("0");
            component.setCreateTime(now);
        }
        component.setCompanyId(source.getCompanyId());
        component.setStationId(source.getStationId());
        component.setComponentType(StringUtils.isEmpty(factoryPid) ? source.getDeviceType() : factoryPid);
        component.setComponentAlias(source.getDisplayName());
        component.setBindStatus("DISABLED".equals(source.getStatus()) ? "UNBOUND" : "ACTIVE");
        component.setUnbindTime("DISABLED".equals(source.getStatus()) ? now : null);
        component.setEnabled("DISABLED".equals(source.getStatus()) ? "1" : "0");
        component.setUpdateTime(now);
        if (component.getId() == null) deviceComponentMapper.insert(component); else deviceComponentMapper.updateById(component);
    }

    private void disableProjection(EmsOpenemsDevice source, EmsDevice target)
    {
        if (target == null)
        {
            return;
        }
        Date now = new Date();
        target.setStatus("DISABLED");
        target.setCommStatus("OFFLINE");
        target.setUpdateTime(now);
        deviceMapper.updateById(target);
        for (EmsDeviceComponent component : deviceComponentMapper.selectList(new LambdaQueryWrapper<EmsDeviceComponent>()
                .eq(EmsDeviceComponent::getTenantId, source.getTenantId())
                .eq(EmsDeviceComponent::getDeviceId, target.getId())
                .eq(EmsDeviceComponent::getServerEndpointId, source.getEndpointId())
                .eq(EmsDeviceComponent::getEdgeId, source.getEdgeId())))
        {
            component.setBindStatus("UNBOUND");
            component.setEnabled("1");
            component.setUnbindTime(now);
            component.setUpdateTime(now);
            deviceComponentMapper.updateById(component);
        }
    }

    private String factoryPid(EmsOpenemsDevice source)
    {
        EmsOpenemsComponent component = openemsComponentMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsComponent>()
                .eq(EmsOpenemsComponent::getTenantId, source.getTenantId())
                .eq(EmsOpenemsComponent::getEndpointId, source.getEndpointId())
                .eq(EmsOpenemsComponent::getEdgeId, source.getEdgeId())
                .eq(EmsOpenemsComponent::getComponentId, source.getPrimaryComponentId())
                .last("limit 1"));
        return component == null ? null : component.getFactoryPid();
    }

    private String businessType(String deviceType, String factoryPid)
    {
        String value = (String.valueOf(deviceType) + " " + String.valueOf(factoryPid)).toLowerCase(Locale.ROOT);
        if (value.contains("managedess") || value.contains("ess")) return "ESS";
        if (value.contains("batteryinverter") || value.contains("pcs")) return "PCS";
        if (value.contains("pvinverter") || value.contains("productionmeter") || value.contains("inverter")) return "INVERTER";
        if (value.contains("evcs") || value.contains("charger")) return "CHARGER";
        if (value.contains("electricitymeter") || value.contains("meter")) return "METER";
        return "OTHER";
    }

    private String stableCode(EmsOpenemsDevice source)
    {
        String raw = "OPENEMS-" + source.getEndpointId() + "-" + source.getEdgeId() + "-" + source.getPrimaryComponentId();
        String normalized = raw.replaceAll("[^A-Za-z0-9_.-]", "_");
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 47) + "-" + sha256(normalized).substring(0, 16);
    }

    private String sha256(String value)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        }
        catch (Exception ex)
        {
            throw new IllegalStateException("生成OpenEMS业务设备编码失败", ex);
        }
    }
}
