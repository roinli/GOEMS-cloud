package com.witos.ems.server.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.witos.ems.server.domain.entity.EmsOpenemsDevice;
import com.witos.ems.server.domain.entity.EmsOpenemsEdge;
import com.witos.ems.server.domain.entity.EmsOpenemsProvisionTask;
import com.witos.ems.server.mapper.EmsOpenemsDeviceMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeMapper;
import com.witos.ems.server.mapper.EmsOpenemsProvisionTaskMapper;
import com.witos.ems.server.openems.OpenemsJsonRpcClient;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsOpenemsProvisionDispatchServiceImplTest
{
    @BeforeAll
    static void initializeLambdaMetadata()
    {
        init(EmsOpenemsProvisionTask.class);
        init(EmsOpenemsDevice.class);
        init(EmsOpenemsEdge.class);
    }

    private static void init(Class<?> type)
    {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }

    @Mock private EmsOpenemsProvisionTaskMapper taskMapper;
    @Mock private EmsOpenemsDeviceMapper deviceMapper;
    @Mock private EmsOpenemsEdgeMapper edgeMapper;
    @Mock private OpenemsJsonRpcClient jsonRpcClient;
    @InjectMocks private EmsOpenemsProvisionDispatchServiceImpl service;

    @Test
    void missingFactoryBecomesUnsupportedWithoutRpcCreate()
    {
        EmsOpenemsProvisionTask task = task();
        when(taskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectOne(any())).thenReturn(device());
        when(edgeMapper.selectOne(any())).thenReturn(edge());
        when(jsonRpcClient.getCapabilitySnapshot(10L, "edge1")).thenReturn(snapshot(false, false, null));

        assertEquals(1, service.dispatchPendingCurrentTenant(10L, "edge1"));
        assertEquals("UNSUPPORTED", task.getState());
        verify(jsonRpcClient, org.mockito.Mockito.never()).createComponentConfig(any(), any(), any(), any());
    }

    @Test
    void existingComponentIsReportedAsConflict()
    {
        EmsOpenemsProvisionTask task = task();
        task.setDesiredJson(desired("meter0", false));
        when(taskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectOne(any())).thenReturn(device());
        when(edgeMapper.selectOne(any())).thenReturn(edge());
        when(jsonRpcClient.getCapabilitySnapshot(10L, "edge1")).thenReturn(snapshot(true, true, "meter0"));

        service.dispatchPendingCurrentTenant(10L, "edge1");

        assertEquals("CONFLICT", task.getState());
        assertEquals("OpenEMS 中已存在：meter0", task.getConflictDetail());
    }

    @Test
    void reusableBridgeIsUsedAndComponentIsVerified()
    {
        EmsOpenemsProvisionTask task = task();
        task.setDesiredJson(desired("meter0", true));
        when(taskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectOne(any())).thenReturn(device());
        when(edgeMapper.selectOne(any())).thenReturn(edge());
        when(jsonRpcClient.getCapabilitySnapshot(10L, "edge1")).thenReturn(snapshot(true, true, null));
        when(jsonRpcClient.getEdgeConfig(10L, "edge1")).thenReturn(config("meter0", "Meter.Simulated"));

        service.dispatchPendingCurrentTenant(10L, "edge1");

        assertEquals("ACTIVE", task.getState());
        assertEquals("meter0", task.getComponentId());
        assertEquals("bridge0", task.getBridgeId());
        verify(jsonRpcClient, org.mockito.Mockito.never()).createComponentConfig(any(), any(),
                org.mockito.ArgumentMatchers.eq("Bridge.Modbus.Tcp"), any());
    }

    @Test
    void newlyCreatedBridgeAndFailedDriverStopAutomaticRetry()
    {
        EmsOpenemsProvisionTask task = task();
        task.setDesiredJson(desired("meter0", true));
        when(taskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectOne(any())).thenReturn(device());
        when(edgeMapper.selectOne(any())).thenReturn(edge());
        when(jsonRpcClient.getCapabilitySnapshot(10L, "edge1")).thenReturn(snapshotWithoutComponents());
        when(jsonRpcClient.createComponentConfig(any(), any(),
                org.mockito.ArgumentMatchers.eq("Bridge.Modbus.Tcp"), any())).thenReturn(Collections.emptyMap());
        doThrow(new RuntimeException("rpc failed")).when(jsonRpcClient).createComponentConfig(any(), any(),
                org.mockito.ArgumentMatchers.eq("Meter.Simulated"), any());

        service.dispatchPendingCurrentTenant(10L, "edge1");

        assertEquals("PARTIAL_FAILED", task.getState());
        assertEquals(1, task.getAttempt());
    }

    @Test
    void appWarningIsPreservedWhenCreatedComponentVerifiesSuccessfully()
    {
        EmsOpenemsProvisionTask task = task();
        task.setDesiredJson(desiredApp());
        when(taskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectOne(any())).thenReturn(device());
        when(edgeMapper.selectOne(any())).thenReturn(edge());
        when(jsonRpcClient.getCapabilitySnapshot(10L, "edge1")).thenReturn(snapshot(true, false, null));
        when(jsonRpcClient.getAppSnapshot(10L, "edge1")).thenReturn(Collections.<String, Object>singletonMap(
                "apps", Collections.singletonList(Collections.<String, Object>singletonMap("appId", "App.Meter"))));
        when(jsonRpcClient.addAppInstance(any(), any(), any(), any(), any(), any())).thenReturn(
                Collections.<String, Object>singletonMap("warnings", Collections.singletonList("需要现场核对相序")));
        when(jsonRpcClient.getEdgeConfig(10L, "edge1")).thenReturn(config("meterApp0", "Meter.Simulated"));

        service.dispatchPendingCurrentTenant(10L, "edge1");

        assertEquals("ACTIVE", task.getState());
        assertEquals("WARNING", JSONObject.parseObject(task.getVerifyJson()).getString("appWarningStatus"));
    }

    @Test
    void timeoutBeforeAnySideEffectStopsAsFailedWithExplicitStep()
    {
        EmsOpenemsProvisionTask task = task();
        task.setDesiredJson(desired("meter0", true));
        when(taskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectOne(any())).thenReturn(device());
        when(edgeMapper.selectOne(any())).thenReturn(edge());
        when(jsonRpcClient.getCapabilitySnapshot(10L, "edge1")).thenReturn(snapshotWithoutComponents());
        doThrow(new RuntimeException("RPC timeout")).when(jsonRpcClient).createComponentConfig(any(), any(),
                org.mockito.ArgumentMatchers.eq("Bridge.Modbus.Tcp"), any());

        service.dispatchPendingCurrentTenant(10L, "edge1");

        assertEquals("FAILED", task.getState());
        assertEquals("TIMEOUT_UNKNOWN", task.getStep());
        assertEquals(1, task.getAttempt());
    }

    @Test
    void directFactoryCreatesOnlyDeviceComponent()
    {
        EmsOpenemsProvisionTask task = task();
        task.setDesiredJson(directDesired("sim0"));
        when(taskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectOne(any())).thenReturn(device());
        when(edgeMapper.selectOne(any())).thenReturn(edge());
        when(jsonRpcClient.getCapabilitySnapshot(10L, "edge1")).thenReturn(snapshot(true, false, null));
        when(jsonRpcClient.createComponentConfig(any(), any(), any(), any())).thenReturn(Collections.emptyMap());
        when(jsonRpcClient.getEdgeConfig(10L, "edge1")).thenReturn(config("sim0", "Meter.Simulated"));

        service.dispatchPendingCurrentTenant(10L, "edge1");

        assertEquals("ACTIVE", task.getState());
        assertEquals("sim0", task.getComponentId());
        assertEquals(null, task.getBridgeId());
        verify(jsonRpcClient).createComponentConfig(org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("edge1"), org.mockito.ArgumentMatchers.eq("Meter.Simulated"), any());
        verify(jsonRpcClient, org.mockito.Mockito.never()).createComponentConfig(any(), any(),
                org.mockito.ArgumentMatchers.startsWith("Bridge."), any());
    }

    @Test
    void simulatorRpcReceivesOnlyCanonicalStandardKeys()
    {
        EmsOpenemsProvisionTask task = task();
        task.setDesiredJson(simulatorDesiredWithLegacyKeys());
        when(taskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(taskMapper.update(any(), any())).thenReturn(1);
        when(deviceMapper.selectOne(any())).thenReturn(device());
        when(edgeMapper.selectOne(any())).thenReturn(edge());
        when(jsonRpcClient.getCapabilitySnapshot(10L, "edge1")).thenReturn(snapshot(true, false, null));
        when(jsonRpcClient.createComponentConfig(any(), any(), any(), any())).thenReturn(Collections.emptyMap());
        when(jsonRpcClient.getEdgeConfig(10L, "edge1")).thenReturn(config("meterLoad0", "Meter.Simulated"));

        service.dispatchPendingCurrentTenant(10L, "edge1");

        ArgumentCaptor<Map<String, Object>> properties = ArgumentCaptor.forClass(Map.class);
        verify(jsonRpcClient).createComponentConfig(org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("edge1"), org.mockito.ArgumentMatchers.eq("Meter.Simulated"),
                properties.capture());
        assertEquals("meterLoad0", properties.getValue().get("id"));
        assertEquals("站点总负载", properties.getValue().get("alias"));
        assertEquals(Boolean.TRUE, properties.getValue().get("enabled"));
        assertEquals("emsSimData5", properties.getValue().get("datasource.id"));
        assertFalse(properties.getValue().containsKey("Alias"));
    }

    private EmsOpenemsProvisionTask task()
    {
        EmsOpenemsProvisionTask task = new EmsOpenemsProvisionTask();
        task.setId(1L);
        task.setTenantId(9999L);
        task.setDeviceId(2L);
        task.setEndpointId(10L);
        task.setEdgeId("edge1");
        task.setState("PENDING_DISPATCH");
        task.setAttempt(0);
        task.setDelFlag("0");
        task.setDesiredJson(desired("", false));
        return task;
    }

    private EmsOpenemsDevice device()
    {
        EmsOpenemsDevice device = new EmsOpenemsDevice();
        device.setId(2L);
        device.setTenantId(9999L);
        device.setStatus("PENDING_DISPATCH");
        device.setDelFlag("0");
        return device;
    }

    private EmsOpenemsEdge edge()
    {
        EmsOpenemsEdge edge = new EmsOpenemsEdge();
        edge.setId(3L);
        edge.setTenantId(9999L);
        edge.setEndpointId(10L);
        edge.setEdgeId("edge1");
        edge.setOnlineStatus("ONLINE");
        edge.setDelFlag("0");
        return edge;
    }

    private Map<String, Object> snapshot(boolean driver, boolean bridge, String componentId)
    {
        JSONObject factories = new JSONObject();
        if (driver) factories.put("Meter.Simulated", new JSONObject());
        if (bridge) factories.put("Bridge.Modbus.Tcp", new JSONObject());
        JSONObject components = new JSONObject();
        if (componentId != null) components.put(componentId, component(componentId, "Meter.Simulated", null));
        else if (bridge) components.put("bridge0", component("bridge0", "Bridge.Modbus.Tcp", "192.168.1.10"));
        Map<String, Object> edgeConfig = new LinkedHashMap<String, Object>();
        edgeConfig.put("factories", factories);
        edgeConfig.put("components", components);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("edgeConfig", edgeConfig);
        result.put("routes", new LinkedHashMap<String, Object>());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshotWithoutComponents()
    {
        Map<String, Object> result = snapshot(true, true, null);
        ((JSONObject) ((Map<String, Object>) result.get("edgeConfig")).get("components")).clear();
        return result;
    }

    private Map<String, Object> component(String id, String factory, String ip)
    {
        Map<String, Object> component = new LinkedHashMap<String, Object>();
        component.put("factoryId", factory);
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        if (ip != null) { properties.put("ip", ip); properties.put("port", 502); }
        component.put("properties", properties);
        return component;
    }

    private Map<String, Object> config(String id, String factory)
    {
        Map<String, Object> components = new LinkedHashMap<String, Object>();
        components.put(id, component(id, factory, null));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("components", components);
        return result;
    }

    private String desired(String componentId, boolean placeholder)
    {
        Map<String, Object> desired = new LinkedHashMap<String, Object>();
        desired.put("driverFactoryPid", "Meter.Simulated");
        desired.put("communicationFactoryPid", "Bridge.Modbus.Tcp");
        desired.put("protocolType", "MODBUS_TCP");
        desired.put("displayName", "电表");
        desired.put("componentId", componentId);
        desired.put("parameters", new LinkedHashMap<String, Object>() {{ put("ip", "192.168.1.10"); put("port", 502); }});
        desired.put("driverProperties", new LinkedHashMap<String, Object>() {{
            if (placeholder) put("bridge", "${bridgeId}");
        }});
        desired.put("bridgeReferenceProperty", "bridge");
        return JSONObject.toJSONString(desired);
    }

    private String desiredApp()
    {
        Map<String, Object> desired = new LinkedHashMap<String, Object>();
        desired.put("driverFactoryPid", "Meter.Simulated");
        desired.put("displayName", "App电表");
        desired.put("appId", "App.Meter");
        desired.put("appKey", "app-meter-1");
        desired.put("appProperties", Collections.emptyMap());
        return JSONObject.toJSONString(desired);
    }

    private String directDesired(String componentId)
    {
        Map<String, Object> desired = new LinkedHashMap<String, Object>();
        desired.put("driverFactoryPid", "Meter.Simulated");
        desired.put("communicationFactoryPid", "");
        desired.put("protocolType", "DIRECT");
        desired.put("displayName", "直连模拟电表");
        desired.put("componentId", componentId);
        desired.put("driverProperties", Collections.singletonMap("power", 1000));
        return JSONObject.toJSONString(desired);
    }

    private String simulatorDesiredWithLegacyKeys()
    {
        Map<String, Object> desired = new LinkedHashMap<String, Object>();
        desired.put("templateKind", "SIMULATOR");
        desired.put("protocolType", "SIMULATOR");
        desired.put("driverFactoryPid", "Meter.Simulated");
        desired.put("displayName", "站点总负载");
        desired.put("primaryComponentRule", Collections.singletonMap("componentId", "meterLoad0"));
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("Alias", "旧别名");
        properties.put("alias", "另一个旧别名");
        properties.put("enabled", false);
        properties.put("datasource.id", "emsSimData5");
        desired.put("appProperties", properties);
        return JSONObject.toJSONString(desired);
    }
}
