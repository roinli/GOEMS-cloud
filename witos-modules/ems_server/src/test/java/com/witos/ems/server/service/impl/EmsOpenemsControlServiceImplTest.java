package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.witos.common.core.exception.ServiceException;
import com.witos.ems.server.domain.entity.EmsOpenemsCapability;
import com.witos.ems.server.domain.entity.EmsOpenemsCommand;
import com.witos.ems.server.domain.entity.EmsOpenemsComponent;
import com.witos.ems.server.domain.entity.EmsOpenemsDevice;
import com.witos.ems.server.domain.entity.EmsOpenemsEdge;
import com.witos.ems.server.mapper.EmsOpenemsCapabilityMapper;
import com.witos.ems.server.mapper.EmsOpenemsCommandMapper;
import com.witos.ems.server.mapper.EmsOpenemsComponentMapper;
import com.witos.ems.server.mapper.EmsOpenemsDeviceMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeMapper;
import com.witos.ems.server.openems.OpenemsJsonRpcClient;
import com.witos.ems.server.service.EmsOpenemsCapabilityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsOpenemsControlServiceImplTest
{
    @BeforeAll
    static void initializeLambdaMetadata()
    {
        init(EmsOpenemsDevice.class); init(EmsOpenemsEdge.class); init(EmsOpenemsComponent.class);
        init(EmsOpenemsCapability.class); init(EmsOpenemsCommand.class);
    }

    private static void init(Class<?> type)
    {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }

    @Mock private EmsOpenemsDeviceMapper deviceMapper;
    @Mock private EmsOpenemsEdgeMapper edgeMapper;
    @Mock private EmsOpenemsComponentMapper componentMapper;
    @Mock private EmsOpenemsCapabilityMapper capabilityMapper;
    @Mock private EmsOpenemsCommandMapper commandMapper;
    @Mock private OpenemsJsonRpcClient jsonRpcClient;
    @Mock private EmsOpenemsCapabilityService capabilityService;
    @InjectMocks private EmsOpenemsControlServiceImpl service;

    @BeforeEach
    void setUp()
    {
        when(deviceMapper.selectOne(any())).thenReturn(device());
        when(edgeMapper.selectOne(any())).thenReturn(edge());
        when(commandMapper.selectOne(any())).thenReturn(null);
        when(commandMapper.insert(any())).thenAnswer(invocation -> {
            ((EmsOpenemsCommand) invocation.getArgument(0)).setId(100L);
            return 1;
        });
    }

    @Test
    void simulatorDataChannelCanBeWrittenAndAudited()
    {
        when(capabilityMapper.selectOne(any())).thenReturn(channel("RW"));
        when(jsonRpcClient.setChannelValue(10L, "edge1", "datasource0", "Data", 5000))
                .thenReturn(Collections.emptyMap());
        Map<String, Object> body = body("SET_SIMULATOR_POWER", "CHANNEL");
        body.put("channelId", "Data"); body.put("value", 5000);

        Map<String, Object> result = service.control(1L, body);

        assertEquals("SUCCESS", result.get("status"));
        verify(jsonRpcClient).setChannelValue(10L, "edge1", "datasource0", "Data", 5000);
    }

    @Test
    void readOnlyChannelIsRejectedAndFailureIsAudited()
    {
        when(capabilityMapper.selectOne(any())).thenReturn(channel("RO"));
        Map<String, Object> body = body("SET_CHANNEL_VALUE", "CHANNEL");
        body.put("channelId", "Data"); body.put("value", 1);

        assertThrows(ServiceException.class, () -> service.control(1L, body));

        ArgumentCaptor<EmsOpenemsCommand> command = ArgumentCaptor.forClass(EmsOpenemsCommand.class);
        verify(commandMapper).updateById(command.capture());
        assertEquals("FAILED", command.getValue().getStatus());
    }

    @Test
    void essFixedPowerUsesOfficialControllerFactory()
    {
        when(capabilityMapper.selectOne(any())).thenReturn(factory("Controller.Ess.FixActivePower"));
        Map<String, Object> scheduler = new LinkedHashMap<String, Object>();
        scheduler.put("factoryId", "Scheduler.AllAlphabetically");
        scheduler.put("properties", Collections.emptyMap());
        Map<String, Object> components = new LinkedHashMap<String, Object>();
        components.put("scheduler0", scheduler);
        when(jsonRpcClient.getEdgeConfig(10L, "edge1"))
                .thenReturn(Collections.<String, Object>singletonMap("components", components));
        when(jsonRpcClient.createComponentConfig(any(), any(), any(), any())).thenReturn(Collections.emptyMap());
        Map<String, Object> body = body("ESS_FIXED_POWER", "CONFIG");
        body.put("value", -3000);

        Map<String, Object> result = service.control(1L, body);

        assertEquals("SUCCESS", result.get("status"));
        verify(jsonRpcClient).createComponentConfig(org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("edge1"),
                org.mockito.ArgumentMatchers.eq("Controller.Ess.FixActivePower"), any());
    }

    @Test
    void offlineEdgeRejectsControlAndWritesFailureAudit()
    {
        EmsOpenemsEdge offline = edge();
        offline.setOnlineStatus("OFFLINE");
        when(edgeMapper.selectOne(any())).thenReturn(offline);
        Map<String, Object> request = body("SET_SIMULATOR_POWER", "CHANNEL");
        request.put("channelId", "Data"); request.put("value", 1000);

        ServiceException error = assertThrows(ServiceException.class, () -> service.control(1L, request));

        assertEquals("Edge离线，不能执行控制", error.getMessage());
        ArgumentCaptor<EmsOpenemsCommand> command = ArgumentCaptor.forClass(EmsOpenemsCommand.class);
        verify(commandMapper).updateById(command.capture());
        assertEquals("FAILED", command.getValue().getStatus());
    }

    @Test
    void expiredChannelCapabilityUsesCachedControlMetadata()
    {
        EmsOpenemsCapability expired = channel("RW");
        expired.setLastSeenAt(new Date(System.currentTimeMillis() - 11L * 60L * 1000L));
        when(capabilityMapper.selectOne(any())).thenReturn(expired);
        when(jsonRpcClient.setChannelValue(10L, "edge1", "datasource0", "Data", 1000))
                .thenReturn(Collections.emptyMap());
        Map<String, Object> request = body("SET_SIMULATOR_POWER", "CHANNEL");
        request.put("channelId", "Data"); request.put("value", 1000);

        Map<String, Object> result = service.control(1L, request);

        assertEquals("SUCCESS", result.get("status"));
        verifyNoInteractions(capabilityService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fixedOrderSchedulerReceivesNewControllerId()
    {
        when(capabilityMapper.selectOne(any())).thenReturn(factory("Controller.Ess.FixActivePower"));
        Map<String, Object> schedulerProperties = new LinkedHashMap<String, Object>();
        schedulerProperties.put("controllers.ids", Arrays.asList("ctrlExisting"));
        Map<String, Object> scheduler = new LinkedHashMap<String, Object>();
        scheduler.put("factoryId", "Scheduler.FixedOrder");
        scheduler.put("properties", schedulerProperties);
        when(jsonRpcClient.getEdgeConfig(10L, "edge1")).thenReturn(Collections.<String, Object>singletonMap(
                "components", Collections.<String, Object>singletonMap("scheduler0", scheduler)));
        when(jsonRpcClient.createComponentConfig(any(), any(), any(), any())).thenReturn(Collections.emptyMap());
        when(jsonRpcClient.updateComponentConfig(any(), any(), any(), any())).thenReturn(Collections.emptyMap());
        Map<String, Object> request = body("ESS_FIXED_POWER", "CONFIG");
        request.put("value", 2500);

        service.control(1L, request);

        ArgumentCaptor<Map<String, Object>> update = ArgumentCaptor.forClass(Map.class);
        verify(jsonRpcClient).updateComponentConfig(org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("edge1"), org.mockito.ArgumentMatchers.eq("scheduler0"), update.capture());
        assertEquals(Arrays.asList("ctrlExisting", "ctrlEms1"), update.getValue().get("controllers.ids"));
    }

    private EmsOpenemsDevice device()
    {
        EmsOpenemsDevice value = new EmsOpenemsDevice();
        value.setId(1L); value.setTenantId(9999L); value.setEndpointId(10L); value.setEdgeId("edge1");
        value.setPrimaryComponentId("datasource0"); value.setDisplayName("模拟设备"); value.setStatus("ACTIVE"); value.setDelFlag("0");
        return value;
    }

    private EmsOpenemsEdge edge()
    {
        EmsOpenemsEdge value = new EmsOpenemsEdge();
        value.setId(2L); value.setTenantId(9999L); value.setEndpointId(10L); value.setEdgeId("edge1");
        value.setOnlineStatus("ONLINE"); value.setDelFlag("0"); return value;
    }

    private EmsOpenemsCapability channel(String accessMode)
    {
        EmsOpenemsCapability value = new EmsOpenemsCapability();
        value.setStatus("ACTIVE"); value.setLastSeenAt(new Date());
        value.setChannelSchema("{\"id\":\"Data\",\"accessMode\":\"" + accessMode + "\",\"type\":\"INTEGER\"}");
        return value;
    }

    private EmsOpenemsCapability factory(String factory)
    {
        EmsOpenemsCapability value = new EmsOpenemsCapability();
        value.setStatus("ACTIVE"); value.setLastSeenAt(new Date()); value.setCapabilityKey("factory:" + factory);
        return value;
    }

    private Map<String, Object> body(String operation, String source)
    {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("requestId", "request-1"); value.put("operation", operation); value.put("operationSource", source);
        return value;
    }
}
