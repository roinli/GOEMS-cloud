package com.witos.ems.server.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.witos.ems.server.domain.entity.EmsOpenemsCapability;
import com.witos.ems.server.domain.entity.EmsOpenemsEdge;
import com.witos.ems.server.domain.entity.EmsOpenemsProtocolTemplate;
import com.witos.ems.server.mapper.EmsOpenemsAppComponentMapper;
import com.witos.ems.server.mapper.EmsOpenemsAppInstanceMapper;
import com.witos.ems.server.mapper.EmsOpenemsAppRelationMapper;
import com.witos.ems.server.mapper.EmsOpenemsCapabilityMapper;
import com.witos.ems.server.mapper.EmsOpenemsComponentMapper;
import com.witos.ems.server.mapper.EmsOpenemsComponentRelationMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeMapper;
import com.witos.ems.server.mapper.EmsOpenemsProtocolTemplateMapper;
import com.witos.ems.server.openems.OpenemsJsonRpcClient;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsOpenemsCapabilityServiceImplTest
{
    @BeforeAll
    static void initTables()
    {
        init(EmsOpenemsEdge.class);
        init(EmsOpenemsCapability.class);
        init(EmsOpenemsProtocolTemplate.class);
    }

    private static void init(Class<?> type)
    {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
    }

    @Mock private EmsOpenemsEdgeMapper edgeMapper;
    @Mock private EmsOpenemsComponentMapper componentMapper;
    @Mock private EmsOpenemsComponentRelationMapper componentRelationMapper;
    @Mock private EmsOpenemsAppInstanceMapper appMapper;
    @Mock private EmsOpenemsAppComponentMapper appComponentMapper;
    @Mock private EmsOpenemsAppRelationMapper appRelationMapper;
    @Mock private EmsOpenemsCapabilityMapper capabilityMapper;
    @Mock private EmsOpenemsProtocolTemplateMapper templateMapper;
    @Mock private OpenemsJsonRpcClient jsonRpcClient;
    @InjectMocks private EmsOpenemsCapabilityServiceImpl service;

    @Test
    void templatesExposeStandardSchemaAndAdvancedJsonMode()
    {
        EmsOpenemsEdge edge = edge();
        EmsOpenemsProtocolTemplate tcp = template("Bridge.Modbus.Tcp", "MODBUS_TCP", "ADAPTED", "ip");
        EmsOpenemsProtocolTemplate unknown = template("Factory.Unknown", "OTHER", "AUTO_GENERATED", "custom");
        when(edgeMapper.selectOne(any())).thenReturn(edge);
        when(templateMapper.selectList(any())).thenReturn(Arrays.asList(tcp, unknown));
        Map<String, Object> result = service.templates(1L);
        assertEquals(2, ((java.util.List<?>) result.get("records")).size());
        Map<?, ?> first = (Map<?, ?>) ((java.util.List<?>) result.get("records")).get(0);
        assertTrue(Boolean.TRUE.equals(first.get("advancedJsonAvailable")));
    }

    @Test
    void refreshStoresRoutesAndFactoriesFromOfficialSnapshot()
    {
        EmsOpenemsEdge edge = edge();
        when(edgeMapper.selectOne(any())).thenReturn(edge);
        when(capabilityMapper.selectOne(any())).thenReturn(null);
        when(capabilityMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(templateMapper.selectOne(any())).thenReturn(null);
        when(jsonRpcClient.getCapabilitySnapshot(10L, "edge1")).thenReturn(snapshot());
        when(templateMapper.selectList(any())).thenReturn(Collections.emptyList());
        Map<String, Object> result = service.refresh(1L);
        assertEquals(1, result.get("routeCount"));
        assertEquals(1, result.get("factoryCount"));
        assertEquals("edge1", result.get("edgeId"));
    }

    @Test
    void refreshUpdatesCapabilityWhenConcurrentInsertWins()
    {
        EmsOpenemsEdge edge = edge();
        when(edgeMapper.selectOne(any())).thenReturn(edge);
        when(capabilityMapper.selectOne(any())).thenReturn(null);
        when(capabilityMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(capabilityMapper.updateByUniqueKey(any())).thenReturn(1);
        when(templateMapper.selectOne(any())).thenReturn(null);
        when(templateMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(jsonRpcClient.getCapabilitySnapshot(10L, "edge1")).thenReturn(snapshot());
        doThrow(new DuplicateKeyException("concurrent refresh")).when(capabilityMapper).insert(any());

        Map<String, Object> result = service.refresh(1L);

        assertEquals(1, result.get("routeCount"));
        verify(capabilityMapper, atLeastOnce()).updateByUniqueKey(any());
    }

    @Test
    void refreshStoresNormalizedAppAssistantFields()
    {
        EmsOpenemsEdge edge = edge();
        when(edgeMapper.selectOne(any())).thenReturn(edge);
        when(capabilityMapper.selectOne(any())).thenReturn(null);
        when(capabilityMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(templateMapper.selectOne(any())).thenReturn(null);
        when(templateMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(jsonRpcClient.getCapabilitySnapshot(10L, "edge1")).thenReturn(snapshot());

        Map<String, Object> status = Collections.singletonMap("name", "INSTALLABLE");
        Map<String, Object> app = new HashMap<String, Object>();
        app.put("appId", "App.Meter.Janitza");
        app.put("name", "Janitza Zähler");
        app.put("status", status);
        when(jsonRpcClient.getAppSnapshot(10L, "edge1"))
                .thenReturn(Collections.singletonMap("apps", Collections.singletonList(app)));

        Map<String, Object> modelOptions = new HashMap<String, Object>();
        modelOptions.put("label", "Product model");
        modelOptions.put("required", true);
        modelOptions.put("options", Arrays.asList(
                option("Janitza UMG 96RM-E", "Meter.Janitza.UMG96RME"),
                option("Janitza UMG 604-PRO", "Meter.Janitza.UMG604")));
        Map<String, Object> model = new HashMap<String, Object>();
        model.put("key", "MODEL");
        model.put("type", "select");
        model.put("defaultValue", "Meter.Janitza.UMG96RME");
        model.put("templateOptions", modelOptions);
        Map<String, Object> alias = new HashMap<String, Object>();
        alias.put("key", "ALIAS");
        alias.put("type", "input");
        alias.put("templateOptions", Collections.singletonMap("label", "Alias"));
        Map<String, Object> assistant = new HashMap<String, Object>();
        assistant.put("name", "Janitza Zähler");
        assistant.put("alias", "Janitza Zähler");
        assistant.put("fields", Arrays.asList(alias, model));
        when(jsonRpcClient.componentJsonApi(org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("edge1"), org.mockito.ArgumentMatchers.eq("_appManager"),
                org.mockito.ArgumentMatchers.eq("getAppAssistant"), any())).thenReturn(assistant);

        service.refresh(1L);

        ArgumentCaptor<EmsOpenemsProtocolTemplate> templates = ArgumentCaptor.forClass(EmsOpenemsProtocolTemplate.class);
        verify(templateMapper, atLeastOnce()).insert(templates.capture());
        EmsOpenemsProtocolTemplate appTemplate = templates.getAllValues().stream()
                .filter(value -> "App.Meter.Janitza".equals(value.getAppId())).findFirst()
                .orElseThrow(() -> new AssertionError("Janitza App template was not stored"));
        JSONObject schema = JSON.parseObject(appTemplate.getSchemaJson());
        assertTrue(schema.getBooleanValue("assistantAvailable"));
        assertEquals(1, schema.getJSONArray("fields").size());
        assertEquals("MODEL", schema.getJSONArray("fields").getJSONObject(0).getString("key"));
        assertEquals(2, schema.getJSONArray("fields").getJSONObject(0).getJSONArray("options").size());
        assertEquals("Meter.Janitza.UMG96RME",
                JSON.parseObject(appTemplate.getDefaultJson()).getString("MODEL"));
    }

    private Map<String, Object> option(String label, String value)
    {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("label", label);
        result.put("value", value);
        return result;
    }

    private EmsOpenemsEdge edge()
    {
        EmsOpenemsEdge edge = new EmsOpenemsEdge();
        edge.setId(1L);
        edge.setTenantId(9999L);
        edge.setEndpointId(10L);
        edge.setEdgeId("edge1");
        edge.setOnlineStatus("ONLINE");
        edge.setDelFlag("0");
        return edge;
    }

    private EmsOpenemsProtocolTemplate template(String factory, String protocol, String status, String key)
    {
        EmsOpenemsProtocolTemplate row = new EmsOpenemsProtocolTemplate();
        row.setId(1L);
        row.setFactoryPid(factory);
        row.setProtocolType(protocol);
        row.setAdaptationStatus(status);
        row.setSchemaJson("{\"fields\":[{\"key\":\"" + key + "\"}]}");
        row.setDefaultJson("{}");
        row.setEnabled("0");
        row.setDelFlag("0");
        return row;
    }

    private Map<String, Object> snapshot()
    {
        Map<String, Object> routes = new HashMap<String, Object>();
        routes.put("version", 1);
        routes.put("endpoints", Collections.singletonList(Collections.singletonMap("method", "routes")));
        Map<String, Object> edgeConfig = new HashMap<String, Object>();
        edgeConfig.put("factories", Collections.singletonMap("Bridge.Modbus.Tcp", Collections.singletonMap("properties", Collections.emptyList())));
        edgeConfig.put("components", Collections.emptyMap());
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("routes", routes);
        result.put("edgeConfig", edgeConfig);
        return result;
    }
}
