package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.witos.common.core.exception.ServiceException;
import com.witos.ems.server.domain.entity.EmsOpenemsCapability;
import com.witos.ems.server.domain.entity.EmsOpenemsDevice;
import com.witos.ems.server.domain.entity.EmsOpenemsEdge;
import com.witos.ems.server.domain.entity.EmsOpenemsProtocolTemplate;
import com.witos.ems.server.domain.entity.EmsOpenemsProvisionTask;
import com.witos.ems.server.mapper.EmsOpenemsCapabilityMapper;
import com.witos.ems.server.mapper.EmsOpenemsDeviceMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeMapper;
import com.witos.ems.server.mapper.EmsOpenemsProtocolTemplateMapper;
import com.witos.ems.server.mapper.EmsOpenemsProvisionTaskMapper;
import com.witos.ems.server.service.EmsOpenemsBindingService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsOpenemsProvisionServiceImplTest
{
    @BeforeAll
    static void initializeLambdaMetadata()
    {
        init(EmsOpenemsEdge.class);
        init(EmsOpenemsDevice.class);
        init(EmsOpenemsProvisionTask.class);
        init(EmsOpenemsProtocolTemplate.class);
        init(EmsOpenemsCapability.class);
    }

    private static void init(Class<?> type)
    {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }

    @Mock private EmsOpenemsEdgeMapper edgeMapper;
    @Mock private EmsOpenemsDeviceMapper deviceMapper;
    @Mock private EmsOpenemsProvisionTaskMapper taskMapper;
    @Mock private EmsOpenemsProtocolTemplateMapper templateMapper;
    @Mock private EmsOpenemsCapabilityMapper capabilityMapper;
    @Mock private EmsOpenemsBindingService bindingService;
    @InjectMocks private EmsOpenemsProvisionServiceImpl service;

    @Test
    void offlineEdgeIsSavedAsPendingWithoutCompanyBinding()
    {
        EmsOpenemsEdge edge = edge("OFFLINE");
        when(edgeMapper.selectOne(any())).thenReturn(edge);
        when(templateMapper.selectOne(any())).thenReturn(null);
        when(taskMapper.selectOne(any())).thenReturn(null);
        when(deviceMapper.insert(any())).thenAnswer(invocation -> {
            ((EmsOpenemsDevice) invocation.getArgument(0)).setId(11L);
            return 1;
        });
        when(taskMapper.insert(any())).thenAnswer(invocation -> {
            ((EmsOpenemsProvisionTask) invocation.getArgument(0)).setId(21L);
            return 1;
        });

        Map<String, Object> result = service.create(request());

        assertEquals("PENDING_DISPATCH", result.get("state"));
        ArgumentCaptor<EmsOpenemsDevice> device = ArgumentCaptor.forClass(EmsOpenemsDevice.class);
        verify(deviceMapper).insert(device.capture());
        assertEquals("PENDING_DISPATCH", device.getValue().getStatus());
        assertNull(device.getValue().getCompanyId());
        assertNull(device.getValue().getStationId());
        verify(bindingService).inheritNewDevice(org.mockito.ArgumentMatchers.eq(11L), any(),
                org.mockito.ArgumentMatchers.eq("INHERITED"));
    }

    @Test
    void onlineEdgeRejectsMissingFactory()
    {
        when(edgeMapper.selectOne(any())).thenReturn(edge("ONLINE"));
        when(templateMapper.selectOne(any())).thenReturn(null);
        when(capabilityMapper.selectCount(any())).thenReturn(0L);

        ServiceException error = assertThrows(ServiceException.class, () -> service.preview(request()));

        assertTrue(error.getMessage().contains("未安装Factory"));
    }

    @Test
    void requiredProtocolParameterIsValidatedLocally()
    {
        when(edgeMapper.selectOne(any())).thenReturn(edge("OFFLINE"));
        EmsOpenemsProtocolTemplate template = template("{\"fields\":[{\"key\":\"ip\",\"required\":true}]}");
        when(templateMapper.selectOne(any())).thenReturn(template);

        ServiceException error = assertThrows(ServiceException.class, () -> service.preview(request()));

        assertEquals("协议参数不能为空：ip", error.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void advancedJsonAndFormParametersShareOneDesiredModel()
    {
        when(edgeMapper.selectOne(any())).thenReturn(edge("OFFLINE"));
        when(templateMapper.selectOne(any())).thenReturn(null);
        Map<String, Object> body = request();
        body.put("parameters", java.util.Collections.singletonMap("ip", "192.168.1.10"));
        body.put("advancedJson", java.util.Collections.singletonMap("componentId", "meter0"));

        Map<String, Object> preview = service.preview(body);
        Map<String, Object> desired = (Map<String, Object>) preview.get("desiredConfig");

        assertEquals("meter0", desired.get("componentId"));
        assertEquals("192.168.1.10", ((Map<String, Object>) desired.get("parameters")).get("ip"));
        assertEquals("ADVANCED_JSON", preview.get("componentIdSource"));
    }

    @Test
    void duplicateActiveDesiredHashIsRejected()
    {
        when(edgeMapper.selectOne(any())).thenReturn(edge("OFFLINE"));
        when(templateMapper.selectOne(any())).thenReturn(null);
        EmsOpenemsProvisionTask duplicate = new EmsOpenemsProvisionTask();
        duplicate.setId(99L);
        when(taskMapper.selectOne(any())).thenReturn(duplicate);

        ServiceException error = assertThrows(ServiceException.class, () -> service.create(request()));

        assertEquals("相同设备配置已存在或正在下发", error.getMessage());
    }

    @Test
    void failedTaskCanBeModifiedAndResubmittedWithoutCreatingAnotherDevice()
    {
        EmsOpenemsProvisionTask task = task("FAILED");
        EmsOpenemsDevice device = new EmsOpenemsDevice();
        device.setId(11L);
        device.setTenantId(9999L);
        device.setDelFlag("0");
        when(taskMapper.selectOne(any())).thenReturn(task, null);
        when(edgeMapper.selectOne(any())).thenReturn(edge("OFFLINE"));
        when(templateMapper.selectOne(any())).thenReturn(null);
        when(deviceMapper.selectOne(any())).thenReturn(device);

        Map<String, Object> body = request();
        body.put("displayName", "修改后的电表");
        Map<String, Object> result = service.retry(21L, body);

        assertEquals("PENDING_DISPATCH", result.get("state"));
        ArgumentCaptor<EmsOpenemsProvisionTask> updatedTask = ArgumentCaptor.forClass(EmsOpenemsProvisionTask.class);
        verify(taskMapper).updateById(updatedTask.capture());
        assertEquals("PENDING_DISPATCH", updatedTask.getValue().getState());
        assertEquals("WAIT_EDGE_ONLINE", updatedTask.getValue().getStep());
        assertEquals(0, updatedTask.getValue().getAttempt());
        assertNull(updatedTask.getValue().getLastError());
        ArgumentCaptor<EmsOpenemsDevice> updatedDevice = ArgumentCaptor.forClass(EmsOpenemsDevice.class);
        verify(deviceMapper).updateById(updatedDevice.capture());
        assertEquals("修改后的电表", updatedDevice.getValue().getDisplayName());
        assertEquals("PENDING_DISPATCH", updatedDevice.getValue().getStatus());
    }

    @Test
    void activeTaskCannotBeResubmitted()
    {
        when(taskMapper.selectOne(any())).thenReturn(task("ACTIVE"));

        ServiceException error = assertThrows(ServiceException.class, () -> service.retry(21L, request()));

        assertEquals("只有已停止的失败任务可以修改后重新提交", error.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void directFactoryPreviewDoesNotRequireCommunicationTemplate()
    {
        when(edgeMapper.selectOne(any())).thenReturn(edge("OFFLINE"));
        Map<String, Object> body = request();
        body.put("protocolType", "DIRECT");
        body.put("driverProperties", java.util.Collections.singletonMap("power", 1000));

        Map<String, Object> preview = service.preview(body);
        Map<String, Object> desired = (Map<String, Object>) preview.get("desiredConfig");

        assertEquals("DIRECT", desired.get("protocolType"));
        assertEquals("", desired.get("communicationFactoryPid"));
        assertEquals(1, ((java.util.List<?>) preview.get("changes")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void simulatorDisplayNamesAreNormalizedToFactoryPropertyIds()
    {
        when(edgeMapper.selectOne(any())).thenReturn(edge("OFFLINE"));
        EmsOpenemsProtocolTemplate template = template("{\"fields\":["
                + "{\"id\":\"id\",\"name\":\"Component-ID\",\"isRequired\":true},"
                + "{\"id\":\"alias\",\"name\":\"Alias\"},"
                + "{\"id\":\"enabled\",\"name\":\"Is enabled?\",\"isRequired\":true},"
                + "{\"id\":\"datasource.id\",\"name\":\"Datasource-ID\",\"isRequired\":true}]}" );
        template.setFactoryPid("Simulator.NRCMeter.Acting");
        template.setProtocolType("SIMULATOR");
        when(templateMapper.selectOne(any())).thenReturn(template);

        Map<String, Object> body = request();
        body.put("templateId", 5L);
        body.put("driverFactoryPid", "Simulator.NRCMeter.Acting");
        Map<String, Object> legacy = new LinkedHashMap<String, Object>();
        legacy.put("Component-ID", "meterLoad0");
        legacy.put("Alias", "站点总负载");
        legacy.put("Is enabled?", true);
        legacy.put("Datasource-ID", "emsSimData5");
        body.put("parameters", legacy);
        body.put("appProperties", legacy);

        Map<String, Object> preview = service.preview(body);
        Map<String, Object> desired = (Map<String, Object>) preview.get("desiredConfig");
        Map<String, Object> properties = (Map<String, Object>) desired.get("appProperties");
        Map<String, Object> primaryRule = (Map<String, Object>) desired.get("primaryComponentRule");

        assertEquals("meterLoad0", properties.get("id"));
        assertEquals("站点总负载", properties.get("alias"));
        assertEquals(Boolean.TRUE, properties.get("enabled"));
        assertEquals("emsSimData5", properties.get("datasource.id"));
        assertEquals("meterLoad0", primaryRule.get("componentId"));
        assertEquals(4, properties.size());
    }

    private EmsOpenemsEdge edge(String status)
    {
        EmsOpenemsEdge edge = new EmsOpenemsEdge();
        edge.setId(1L);
        edge.setTenantId(9999L);
        edge.setEndpointId(10L);
        edge.setEdgeId("edge1");
        edge.setOnlineStatus(status);
        edge.setDelFlag("0");
        return edge;
    }

    private EmsOpenemsProtocolTemplate template(String schema)
    {
        EmsOpenemsProtocolTemplate template = new EmsOpenemsProtocolTemplate();
        template.setId(5L);
        template.setTenantId(9999L);
        template.setEndpointId(10L);
        template.setFactoryPid("Bridge.Modbus.Tcp");
        template.setProtocolType("MODBUS_TCP");
        template.setSchemaJson(schema);
        template.setEnabled("0");
        template.setDelFlag("0");
        return template;
    }

    private EmsOpenemsProvisionTask task(String state)
    {
        EmsOpenemsProvisionTask task = new EmsOpenemsProvisionTask();
        task.setId(21L);
        task.setTenantId(9999L);
        task.setDeviceId(11L);
        task.setEndpointId(10L);
        task.setEdgeId("edge1");
        task.setState(state);
        task.setAttempt(1);
        task.setDelFlag("0");
        return task;
    }

    private Map<String, Object> request()
    {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("edgeId", 1L);
        body.put("displayName", "测试电表");
        body.put("deviceType", "METER");
        body.put("driverFactoryPid", "Meter.Simulated");
        body.put("protocolType", "MODBUS_TCP");
        body.put("parameters", new HashMap<String, Object>());
        return body;
    }
}
