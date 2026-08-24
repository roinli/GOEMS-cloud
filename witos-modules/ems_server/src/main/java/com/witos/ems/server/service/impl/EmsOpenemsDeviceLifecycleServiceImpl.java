package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.domain.entity.EmsOpenemsDevice;
import com.witos.ems.server.domain.entity.EmsOpenemsProvisionTask;
import com.witos.ems.server.mapper.EmsOpenemsDeviceMapper;
import com.witos.ems.server.mapper.EmsOpenemsProvisionTaskMapper;
import com.witos.ems.server.service.EmsOpenemsDeviceLifecycleService;
import com.witos.ems.server.service.EmsOpenemsBusinessProjectionService;
import com.witos.ems.server.service.EmsOpenemsTimeseriesService;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Date;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class EmsOpenemsDeviceLifecycleServiceImpl implements EmsOpenemsDeviceLifecycleService
{
    @Resource
    private EmsOpenemsDeviceMapper deviceMapper;

    @Resource
    private EmsOpenemsProvisionTaskMapper provisionTaskMapper;

    @Resource
    private EmsOpenemsTimeseriesService timeseriesService;

    @Resource
    private EmsOpenemsBusinessProjectionService businessProjectionService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> disable(Long deviceId)
    {
        EmsOpenemsDevice device = requireDevice(deviceId);
        if (!"DISABLED".equals(device.getStatus()))
        {
            device.setStatus("DISABLED");
            device.setDisabledAt(new Date());
            device.setUpdateTime(new Date());
            deviceMapper.updateById(device);
            if (businessProjectionService != null) businessProjectionService.syncDevice(device);
        }
        return lifecycleView(device);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> enable(Long deviceId)
    {
        EmsOpenemsDevice device = requireDevice(deviceId);
        if (!"DISABLED".equals(device.getStatus()))
        {
            throw new ServiceException("设备当前不是停用状态");
        }
        Date disabledAt = device.getDisabledAt();
        device.setStatus("ACTIVE");
        device.setDisabledAt(null);
        device.setUpdateTime(new Date());
        deviceMapper.updateById(device);
        if (businessProjectionService != null) businessProjectionService.syncDevice(device);
        Map<String, Object> result = lifecycleView(device);
        result.put("previousDisabledAt", disabledAt);
        result.put("resumeFrom", device.getUpdateTime());
        result.put("historyAutoRecovered", false);
        result.put("backfillAvailable", true);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> removeFailed(Long deviceId)
    {
        EmsOpenemsDevice device = requireDevice(deviceId);
        EmsOpenemsProvisionTask task = provisionTaskMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsProvisionTask>()
                .eq(EmsOpenemsProvisionTask::getTenantId, device.getTenantId())
                .eq(EmsOpenemsProvisionTask::getDeviceId, device.getId())
                .eq(EmsOpenemsProvisionTask::getDelFlag, "0")
                .orderByDesc(EmsOpenemsProvisionTask::getId).last("limit 1"));
        if (task == null || !Arrays.asList("FAILED", "UNSUPPORTED", "CONFLICT", "DISABLED").contains(task.getState()))
        {
            throw new ServiceException("只有创建前失败且未落到Edge的设备可以删除");
        }
        boolean conflict = "CONFLICT".equals(task.getState());
        if (!StringUtils.isEmpty(task.getBridgeId()) || !StringUtils.isEmpty(task.getVerifyJson())
                || (!conflict && !StringUtils.isEmpty(task.getComponentId())) || !StringUtils.isEmpty(device.getRawJson()))
        {
            throw new ServiceException("该任务可能已在Edge产生配置，请先人工核对，不能直接删除");
        }
        int deletedTasks = provisionTaskMapper.delete(new LambdaQueryWrapper<EmsOpenemsProvisionTask>()
                .eq(EmsOpenemsProvisionTask::getTenantId, device.getTenantId())
                .eq(EmsOpenemsProvisionTask::getDeviceId, device.getId())
                .eq(EmsOpenemsProvisionTask::getDelFlag, "0"));
        if (deviceMapper.deleteById(device.getId()) <= 0)
        {
            throw new ServiceException("失败设备记录删除失败");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("deviceId", device.getId());
        result.put("deletedProvisionTasks", deletedTasks);
        result.put("openemsComponentDeleted", false);
        result.put("localOnly", true);
        return result;
    }

    @Override
    public Map<String, Object> resourceReport(Long deviceId, Map<String, String> query)
    {
        EmsOpenemsDevice device = requireDevice(deviceId);
        Map<String, Object> history = timeseriesService.history(deviceId, query);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("reportKey", device.getTenantId() + ":" + device.getEndpointId() + ":"
                + device.getEdgeId() + ":" + device.getPrimaryComponentId());
        result.put("deviceId", device.getId());
        result.put("endpointId", device.getEndpointId());
        result.put("edgeId", device.getEdgeId());
        result.put("primaryComponentId", device.getPrimaryComponentId());
        result.put("companyId", device.getCompanyId());
        result.put("stationId", device.getStationId());
        result.put("deviceStatus", device.getStatus());
        result.put("source", history.get("source"));
        result.put("quality", history.get("quality"));
        result.put("rows", history.get("rows"));
        boolean bound = device.getCompanyId() != null && device.getStationId() != null;
        result.put("includedInCompanyStationSummary", bound && !"DISABLED".equals(device.getStatus()));
        result.put("revenueStatus", bound ? "ELIGIBLE" : "NOT_APPLICABLE");
        result.put("revenueAmount", null);
        result.put("revenueMessage", bound ? "收益由绑定区间和电价规则计算" : "未绑定公司和电站，不计算收益");
        return result;
    }

    private EmsOpenemsDevice requireDevice(Long deviceId)
    {
        EmsOpenemsDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsDevice>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsDevice::getTenantId,
                        EmsRequestSupport.currentTenantId())
                .eq(EmsOpenemsDevice::getId, deviceId)
                .eq(EmsOpenemsDevice::getDelFlag, "0")
                .last("limit 1"));
        if (device == null)
        {
            throw new ServiceException("OpenEMS设备不存在或不属于当前租户");
        }
        return device;
    }

    private Map<String, Object> lifecycleView(EmsOpenemsDevice device)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("deviceId", device.getId());
        result.put("status", device.getStatus());
        result.put("disabledAt", device.getDisabledAt());
        result.put("openemsComponentDeleted", false);
        result.put("influxHistoryDeleted", false);
        result.put("monitoringEnabled", !"DISABLED".equals(device.getStatus()));
        result.put("alarmEvaluationEnabled", !"DISABLED".equals(device.getStatus()));
        result.put("reportMaterializationEnabled", !"DISABLED".equals(device.getStatus()));
        result.put("revenueCalculationEnabled", !"DISABLED".equals(device.getStatus())
                && device.getCompanyId() != null && device.getStationId() != null);
        result.put("controlEnabled", !"DISABLED".equals(device.getStatus()));
        return result;
    }
}
