package com.witos.ems.server.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.domain.entity.EmsOpenemsDevice;
import com.witos.ems.server.domain.entity.EmsOpenemsEdge;
import com.witos.ems.server.domain.entity.EmsOpenemsProvisionTask;
import com.witos.ems.server.mapper.EmsOpenemsDeviceMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeMapper;
import com.witos.ems.server.mapper.EmsOpenemsProvisionTaskMapper;
import com.witos.ems.server.openems.OpenemsJsonRpcClient;
import com.witos.ems.server.service.EmsOpenemsProvisionDispatchService;
import com.witos.ems.server.service.EmsOpenemsBusinessProjectionService;
import com.witos.ems.server.support.EmsRequestSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class EmsOpenemsProvisionDispatchServiceImpl implements EmsOpenemsProvisionDispatchService
{
    @Resource private EmsOpenemsProvisionTaskMapper taskMapper;
    @Resource private EmsOpenemsDeviceMapper deviceMapper;
    @Resource private EmsOpenemsEdgeMapper edgeMapper;
    @Resource private OpenemsJsonRpcClient jsonRpcClient;
    @Resource private EmsOpenemsBusinessProjectionService businessProjectionService;

    @Override
    public int dispatchPendingCurrentTenant(Long endpointId, String edgeId)
    {
        Long tenantId = EmsRequestSupport.currentTenantId();
        List<EmsOpenemsProvisionTask> tasks = taskMapper.selectList(new LambdaQueryWrapper<EmsOpenemsProvisionTask>()
                .eq(EmsOpenemsProvisionTask::getTenantId, tenantId)
                .eq(EmsOpenemsProvisionTask::getEndpointId, endpointId)
                .eq(EmsOpenemsProvisionTask::getEdgeId, edgeId)
                .eq(EmsOpenemsProvisionTask::getState, "PENDING_DISPATCH")
                .eq(EmsOpenemsProvisionTask::getAttempt, 0)
                .eq(EmsOpenemsProvisionTask::getDelFlag, "0")
                .orderByAsc(EmsOpenemsProvisionTask::getCreateTime));
        int affected = 0;
        for (EmsOpenemsProvisionTask task : tasks)
        {
            if (!claim(task, tenantId)) continue;
            affected++;
            dispatch(task, tenantId);
        }
        return affected;
    }

    private boolean claim(EmsOpenemsProvisionTask task, Long tenantId)
    {
        Date now = new Date();
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<EmsOpenemsProvisionTask>()
                .eq(EmsOpenemsProvisionTask::getId, task.getId())
                .eq(EmsOpenemsProvisionTask::getTenantId, tenantId)
                .eq(EmsOpenemsProvisionTask::getState, "PENDING_DISPATCH")
                .eq(EmsOpenemsProvisionTask::getAttempt, 0)
                .set(EmsOpenemsProvisionTask::getState, "PRECHECK")
                .set(EmsOpenemsProvisionTask::getStep, "READ_EDGE_CONFIG")
                .set(EmsOpenemsProvisionTask::getAttempt, 1)
                .set(EmsOpenemsProvisionTask::getStartedAt, now)
                .set(EmsOpenemsProvisionTask::getUpdateTime, now));
        if (updated > 0)
        {
            task.setState("PRECHECK");
            task.setStep("READ_EDGE_CONFIG");
            task.setAttempt(1);
            task.setStartedAt(now);
        }
        return updated > 0;
    }

    private void dispatch(EmsOpenemsProvisionTask task, Long tenantId)
    {
        boolean sideEffect = false;
        EmsOpenemsDevice device = null;
        try
        {
            device = requireDevice(task, tenantId);
            if ("DISABLED".equals(device.getStatus()))
            {
                finish(task, "DISABLED", "DEVICE_DISABLED", "设备已停用，不执行自动下发", null);
                return;
            }
            EmsOpenemsEdge edge = edgeMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsEdge>()
                    .eq(EmsOpenemsEdge::getTenantId, tenantId)
                    .eq(EmsOpenemsEdge::getEndpointId, task.getEndpointId())
                    .eq(EmsOpenemsEdge::getEdgeId, task.getEdgeId())
                    .eq(EmsOpenemsEdge::getDelFlag, "0").last("limit 1"));
            if (edge == null || !"ONLINE".equals(edge.getOnlineStatus()))
            {
                restorePending(task, "Edge已离线，等待下次上线心跳");
                return;
            }
            JSONObject desired = JSON.parseObject(task.getDesiredJson());
            Map<String, Object> capability = jsonRpcClient.getCapabilitySnapshot(task.getEndpointId(), task.getEdgeId());
            JSONObject edgeConfig = JSON.parseObject(JSON.toJSONString(capability.get("edgeConfig")));
            JSONObject factories = edgeConfig.getJSONObject("factories");
            JSONObject componentsBefore = defaultObject(edgeConfig.getJSONObject("components"));
            String templateKind = desired.getString("templateKind");
            boolean simulator = "SIMULATOR".equals(templateKind) || defaultString(desired.getString("protocolType"), "").equals("SIMULATOR");
            String appId = desired.getString("appId");
            if (simulator)
            {
                String factoryPid = defaultString(desired.getString("driverFactoryPid"), desired.getString("communicationFactoryPid"));
                if (StringUtils.isEmpty(factoryPid))
                {
                    finish(task, "UNSUPPORTED", "SIMULATOR_FACTORY_MISSING", "模拟器模板缺少Factory PID", null);
                    updateDeviceStatus(device, "UNSUPPORTED");
                    return;
                }
                ensureFactoryInstalledOrThrow(factories, factoryPid);
                updateProgress(task, "PROVISIONING", "PRECHECK_COMPONENT_CONFLICT");
                String componentId = primaryRule(desired).getString("componentId");
                if (StringUtils.isEmpty(componentId))
                {
                    componentId = "emsDevice" + device.getId();
                }
                if (componentsBefore.containsKey(componentId))
                {
                    conflict(task, componentId);
                    device.setStatus("CONFLICT");
                    device.setUpdateTime(new Date());
                    deviceMapper.updateById(device);
                    return;
                }
                Map<String, Object> properties = new LinkedHashMap<String, Object>(map(defaultObject(desired.getJSONObject("appProperties"))));
                removeCaseInsensitive(properties, "id");
                removeCaseInsensitive(properties, "alias");
                removeCaseInsensitive(properties, "enabled");
                properties.put("id", componentId);
                properties.put("alias", desired.getString("displayName"));
                properties.put("enabled", true);
                jsonRpcClient.createComponentConfig(task.getEndpointId(), task.getEdgeId(), factoryPid, properties);
                sideEffect = true;
                JSONObject verify = new JSONObject();
                verify.put("componentMode", "SIMULATOR");
                applyScheduler(task, desired);
                updateProgress(task, "VERIFYING", "READ_BACK_CONFIG");
                JSONObject actualConfig = JSON.parseObject(JSON.toJSONString(
                        jsonRpcClient.getEdgeConfig(task.getEndpointId(), task.getEdgeId())));
                JSONObject actualComponents = defaultObject(actualConfig.getJSONObject("components"));
                JSONObject actual = actualComponents.getJSONObject(componentId);
                if (actual == null)
                {
                    throw new PartialProvisionException("OpenEMS已接受创建请求，但回读未找到模拟器Component：" + componentId);
                }
                if (!factoryPid.equals(actual.getString("factoryId")))
                {
                    throw new PartialProvisionException("回读模拟器Component Factory不一致：" + componentId);
                }
                verify.put("componentId", componentId);
                verify.put("factoryId", actual.getString("factoryId"));
                verify.put("actualConfigHash", sha256(JSON.toJSONString(actual)));
                verify.put("verified", true);
                task.setComponentId(componentId);
                finish(task, "ACTIVE", null, null, verify);
                device.setPrimaryComponentId(componentId);
                device.setStatus("ACTIVE");
                device.setLastSeenAt(new Date());
                device.setRawJson(actual.toJSONString());
                device.setUpdateTime(new Date());
                deviceMapper.updateById(device);
                if (businessProjectionService != null)
                {
                    try
                    {
                        businessProjectionService.syncDevice(device);
                    }
                    catch (Exception projectionError)
                    {
                        log.error("OpenEMS device provision succeeded but business projection failed, deviceId={}",
                                device.getId(), projectionError);
                    }
                }
                return;
            }
            if (StringUtils.isEmpty(appId))
            {
                finish(task, "UNSUPPORTED", "APP_REQUIRED", "新增设备只允许通过OpenEMS App下发", null);
                updateDeviceStatus(device, "UNSUPPORTED");
                return;
            }
            requireDependencyFactories(factories, desired.getJSONArray("dependencyFactories"));

            updateProgress(task, "PROVISIONING", "PRECHECK_COMPONENT_CONFLICT");
            String componentId = primaryRule(desired).getString("componentId");
            if (!StringUtils.isEmpty(componentId) && componentsBefore.containsKey(componentId))
            {
                conflict(task, componentId);
                device.setStatus("CONFLICT");
                device.setUpdateTime(new Date());
                deviceMapper.updateById(device);
                return;
            }

            JSONObject verify = new JSONObject();
            requireApp(task, appId);
            Set<String> beforeIds = new HashSet<String>(componentsBefore.keySet());
            Map<String, Object> appResult = jsonRpcClient.addAppInstance(task.getEndpointId(), task.getEdgeId(),
                    appId, desired.getString("appKey"), desired.getString("displayName"),
                    map(desired.getJSONObject("appProperties")));
            sideEffect = true;
            verify.put("app", appResult);
            componentId = resolveAppComponent(task, desired, beforeIds);
            verify.put("appWarningStatus", warnings(appResult).isEmpty() ? "NONE" : "WARNING");
            verify.put("appWarnings", warnings(appResult));

            applyScheduler(task, desired);
            updateProgress(task, "VERIFYING", "READ_BACK_CONFIG");
            JSONObject actualConfig = JSON.parseObject(JSON.toJSONString(
                    jsonRpcClient.getEdgeConfig(task.getEndpointId(), task.getEdgeId())));
            JSONObject actualComponents = defaultObject(actualConfig.getJSONObject("components"));
            JSONObject actual = actualComponents.getJSONObject(componentId);
            if (actual == null)
            {
                throw new PartialProvisionException("OpenEMS已接受创建请求，但回读未找到主Component：" + componentId);
            }
            String expectedFactory = primaryRule(desired).getString("factoryPid");
            if (!StringUtils.isEmpty(expectedFactory) && !expectedFactory.equals(actual.getString("factoryId")))
            {
                throw new PartialProvisionException("回读Component Factory不一致：" + componentId);
            }
            verify.put("componentId", componentId);
            verify.put("factoryId", actual.getString("factoryId"));
            verify.put("actualConfigHash", sha256(JSON.toJSONString(actual)));
            verify.put("verified", true);
            task.setComponentId(componentId);
            finish(task, "ACTIVE", null, null, verify);
            device.setPrimaryComponentId(componentId);
            device.setStatus("ACTIVE");
            device.setLastSeenAt(new Date());
            device.setRawJson(actual.toJSONString());
            device.setUpdateTime(new Date());
            deviceMapper.updateById(device);
            if (businessProjectionService != null)
            {
                try
                {
                    businessProjectionService.syncDevice(device);
                }
                catch (Exception projectionError)
                {
                    log.error("OpenEMS device provision succeeded but business projection failed, deviceId={}",
                            device.getId(), projectionError);
                }
            }
        }
        catch (UnsupportedProvisionException ex)
        {
            finish(task, "UNSUPPORTED", ex.code, ex.getMessage(), null);
            updateDeviceStatus(device, "UNSUPPORTED");
        }
        catch (PartialProvisionException ex)
        {
            finish(task, "PARTIAL_FAILED", "VERIFY_FAILED", ex.getMessage(), null);
            updateDeviceStatus(device, "FAILED");
        }
        catch (Exception ex)
        {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            String state = timeout(message) ? "TIMEOUT_UNKNOWN" : (sideEffect || !StringUtils.isEmpty(task.getBridgeId()) ? "PARTIAL_FAILED" : "FAILED");
            String code = timeout(message) ? "TIMEOUT_UNKNOWN" : "OPENEMS_RPC_FAILED";
            finish(task, state, code, message, null);
            updateDeviceStatus(device, "FAILED");
            log.error("OpenEMS device provision failed, taskId={}, endpointId={}, edgeId={}",
                    task.getId(), task.getEndpointId(), task.getEdgeId(), ex);
        }
    }

    private void updateDeviceStatus(EmsOpenemsDevice device, String status)
    {
        if (device == null) return;
        device.setStatus(status);
        device.setUpdateTime(new Date());
        deviceMapper.updateById(device);
    }

    private EmsOpenemsDevice requireDevice(EmsOpenemsProvisionTask task, Long tenantId)
    {
        EmsOpenemsDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsDevice>()
                .eq(EmsOpenemsDevice::getTenantId, tenantId).eq(EmsOpenemsDevice::getId, task.getDeviceId())
                .eq(EmsOpenemsDevice::getDelFlag, "0").last("limit 1"));
        if (device == null) throw new ServiceException("下发任务对应的EMS设备不存在");
        return device;
    }

    private void requireDependencyFactories(JSONObject factories, JSONArray dependencyFactories)
    {
        if (dependencyFactories == null) return;
        for (Object item : dependencyFactories)
        {
            String factoryId = String.valueOf(item);
            if (StringUtils.isEmpty(factoryId)) continue;
            if (factories == null || !factories.containsKey(factoryId))
            {
                throw new UnsupportedProvisionException("FACTORY_NOT_INSTALLED", "目标Edge未安装Factory：" + factoryId);
            }
        }
    }

    private void ensureFactoryInstalledOrThrow(JSONObject factories, String factoryPid)
    {
        if (StringUtils.isEmpty(factoryPid) || factories == null || !factories.containsKey(factoryPid))
        {
            throw new UnsupportedProvisionException("FACTORY_NOT_INSTALLED", "目标Edge未安装Factory：" + factoryPid);
        }
    }

    private void requireApp(EmsOpenemsProvisionTask task, String appId)
    {
        Map<String, Object> snapshot = jsonRpcClient.getAppSnapshot(task.getEndpointId(), task.getEdgeId());
        JSONArray apps = JSON.parseObject(JSON.toJSONString(snapshot)).getJSONArray("apps");
        if (apps != null)
        {
            for (Object item : apps)
            {
                if (appId.equals(JSON.parseObject(JSON.toJSONString(item)).getString("appId"))) return;
            }
        }
        throw new UnsupportedProvisionException("APP_NOT_INSTALLED", "目标Edge未安装App：" + appId);
    }

    private String resolveAppComponent(EmsOpenemsProvisionTask task, JSONObject desired, Set<String> beforeIds)
    {
        JSONObject config = JSON.parseObject(JSON.toJSONString(
                jsonRpcClient.getEdgeConfig(task.getEndpointId(), task.getEdgeId())));
        JSONObject components = defaultObject(config.getJSONObject("components"));
        JSONObject rule = primaryRule(desired);
        String requested = rule.getString("componentId");
        if (!StringUtils.isEmpty(requested) && components.containsKey(requested)) return requested;
        String driverFactory = rule.getString("factoryPid");
        List<String> candidates = new ArrayList<String>();
        for (Map.Entry<String, Object> entry : components.entrySet())
        {
            JSONObject component = JSON.parseObject(JSON.toJSONString(entry.getValue()));
            if (beforeIds.contains(entry.getKey())) continue;
            String factoryId = component.getString("factoryId");
            if (!StringUtils.isEmpty(driverFactory) && !driverFactory.equals(factoryId)) continue;
            if (isInfrastructureFactory(factoryId)) continue;
            if (component.getBooleanValue("enabled") || component.containsKey("properties"))
            {
                candidates.add(entry.getKey());
            }
        }
        if (candidates.size() == 1) return candidates.get(0);
        throw new PartialProvisionException(candidates.isEmpty()
                ? "App已创建，但未找到对应驱动Component" : "App创建了多个同类型Component，无法自动确定主Component");
    }

    private JSONObject primaryRule(JSONObject desired)
    {
        JSONObject rule = desired.getJSONObject("primaryComponentRule");
        return rule == null ? new JSONObject() : rule;
    }

    private void removeCaseInsensitive(Map<String, Object> properties, String key)
    {
        properties.keySet().removeIf(existing -> existing.equalsIgnoreCase(key));
    }

    private boolean isInfrastructureFactory(String factoryId)
    {
        return factoryId != null && (factoryId.startsWith("Bridge.") || factoryId.startsWith("Scheduler.")
                || factoryId.startsWith("Controller.") || factoryId.startsWith("Timedata.")
                || factoryId.startsWith("Host.") || factoryId.startsWith("Core."));
    }

    private String findReusableBridge(JSONObject components, String factoryId, String protocol, JSONObject parameters)
    {
        if (StringUtils.isEmpty(factoryId)) return "";
        Map<String, Object> expected = bridgeProperties(protocol, parameters);
        for (Map.Entry<String, Object> entry : components.entrySet())
        {
            JSONObject component = JSON.parseObject(JSON.toJSONString(entry.getValue()));
            if (!factoryId.equals(component.getString("factoryId"))) continue;
            JSONObject actual = defaultObject(component.getJSONObject("properties"));
            if (containsAll(actual, expected)) return entry.getKey();
        }
        return "";
    }

    private Map<String, Object> bridgeProperties(String protocol, JSONObject source)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String[] keys;
        if ("MODBUS_TCP".equals(protocol)) keys = new String[]{"ip", "port"};
        else if ("MODBUS_SERIAL".equals(protocol)) keys = new String[]{"portName", "baudRate", "databits", "stopbits", "parity"};
        else if ("MQTT".equals(protocol)) keys = new String[]{"host", "port", "secureConnect", "username"};
        else if ("MBUS".equals(protocol)) keys = new String[]{"portName", "baudrate"};
        else if ("ONEWIRE".equals(protocol)) keys = new String[]{"port"};
        else keys = new String[0];
        for (String key : keys) if (source.containsKey(key)) result.put(key, source.get(key));
        return result;
    }

    private void applyMappings(Map<String, Object> target, JSONObject mappings, JSONObject parameters)
    {
        if (mappings == null) return;
        for (Map.Entry<String, Object> entry : mappings.entrySet())
        {
            String targetKey = String.valueOf(entry.getValue());
            if (parameters.containsKey(entry.getKey()) && !StringUtils.isEmpty(targetKey))
            {
                target.put(targetKey, parameters.get(entry.getKey()));
            }
        }
    }

    private boolean containsBridgePlaceholder(JSONObject values)
    {
        for (Object value : values.values()) if ("${bridgeId}".equals(String.valueOf(value))) return true;
        return false;
    }

    private void replaceBridgePlaceholder(Map<String, Object> values, String bridgeId)
    {
        for (Map.Entry<String, Object> entry : values.entrySet())
        {
            if ("${bridgeId}".equals(String.valueOf(entry.getValue()))) entry.setValue(bridgeId);
        }
    }

    private boolean containsAll(JSONObject actual, Map<String, Object> expected)
    {
        for (Map.Entry<String, Object> entry : expected.entrySet())
        {
            if (!String.valueOf(entry.getValue()).equals(String.valueOf(actual.get(entry.getKey())))) return false;
        }
        return true;
    }

    private void applyScheduler(EmsOpenemsProvisionTask task, JSONObject desired)
    {
        JSONObject scheduler = desired.getJSONObject("scheduler");
        if (scheduler == null || scheduler.isEmpty()) return;
        String componentId = scheduler.getString("componentId");
        JSONObject properties = scheduler.getJSONObject("properties");
        if (StringUtils.isEmpty(componentId) || properties == null)
        {
            throw new UnsupportedProvisionException("SCHEDULER_MAPPING_REQUIRED",
                    "Scheduler变更必须明确componentId和properties");
        }
        jsonRpcClient.updateComponentConfig(task.getEndpointId(), task.getEdgeId(), componentId, map(properties));
    }

    private void updateProgress(EmsOpenemsProvisionTask task, String state, String step)
    {
        task.setState(state);
        task.setStep(step);
        task.setUpdateTime(new Date());
        taskMapper.updateById(task);
    }

    private void conflict(EmsOpenemsProvisionTask task, String componentId)
    {
        task.setComponentId(componentId);
        task.setConflictDetail("OpenEMS 中已存在：" + componentId);
        finish(task, "CONFLICT", "COMPONENT_ID_EXISTS", task.getConflictDetail(), null);
    }

    private void restorePending(EmsOpenemsProvisionTask task, String message)
    {
        task.setState("PENDING_DISPATCH");
        task.setStep("WAIT_EDGE_ONLINE");
        task.setAttempt(0);
        task.setLastError(message);
        task.setStartedAt(null);
        task.setUpdateTime(new Date());
        taskMapper.updateById(task);
    }

    private void finish(EmsOpenemsProvisionTask task, String state, String code, String message, JSONObject verify)
    {
        task.setState(state);
        task.setStep(code == null ? "COMPLETED" : code);
        task.setLastError(message);
        task.setVerifyJson(verify == null ? null : verify.toJSONString());
        task.setFinishedAt(new Date());
        task.setUpdateTime(new Date());
        taskMapper.updateById(task);
    }

    private List<Object> warnings(Map<String, Object> response)
    {
        Object value = response == null ? null : response.get("warnings");
        return value instanceof List ? new ArrayList<Object>((List<?>) value) : new ArrayList<Object>();
    }

    private boolean timeout(String message)
    {
        String lower = message == null ? "" : message.toLowerCase();
        return lower.contains("timeout") || lower.contains("timed out") || lower.contains("超时");
    }

    private JSONObject defaultObject(JSONObject value)
    {
        return value == null ? new JSONObject() : value;
    }

    private String defaultString(Object value, String fallback)
    {
        return value == null || StringUtils.isEmpty(String.valueOf(value)) ? fallback : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(JSONObject value)
    {
        return value == null ? new LinkedHashMap<String, Object>() : JSON.parseObject(value.toJSONString(), Map.class);
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
        catch (Exception ex) { throw new ServiceException("配置hash计算失败"); }
    }

    private static class UnsupportedProvisionException extends RuntimeException
    {
        private final String code;

        private UnsupportedProvisionException(String code, String message)
        {
            super(message);
            this.code = code;
        }
    }

    private static class PartialProvisionException extends RuntimeException
    {
        private PartialProvisionException(String message)
        {
            super(message);
        }
    }
}
