package com.witos.ems.server.openems;

import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.witos.ems.server.domain.entity.EmsOpenemsAppComponent;
import com.witos.ems.server.domain.entity.EmsOpenemsAppInstance;
import com.witos.ems.server.domain.entity.EmsOpenemsAppRelation;
import com.witos.ems.server.domain.entity.EmsOpenemsComponent;
import com.witos.ems.server.domain.entity.EmsOpenemsComponentRelation;
import com.witos.ems.server.domain.entity.EmsOpenemsEdge;
import com.witos.ems.server.domain.entity.EmsOpenemsEdgeCreateTask;
import com.witos.ems.server.domain.entity.EmsServerEndpoint;
import com.witos.ems.server.mapper.EmsOpenemsAppComponentMapper;
import com.witos.ems.server.mapper.EmsOpenemsAppInstanceMapper;
import com.witos.ems.server.mapper.EmsOpenemsAppRelationMapper;
import com.witos.ems.server.mapper.EmsOpenemsComponentMapper;
import com.witos.ems.server.mapper.EmsOpenemsComponentRelationMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeCreateTaskMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeMapper;
import com.witos.ems.server.mapper.EmsServerEndpointMapper;
import com.witos.ems.server.service.EmsOpenemsProvisionDispatchService;
import com.witos.ems.server.service.EmsOpenemsCapabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsOpenemsResourceSyncServiceTest
{
    @BeforeAll
    static void initializeLambdaMetadata()
    {
        initTableInfo(EmsOpenemsEdge.class);
        initTableInfo(EmsOpenemsComponent.class);
        initTableInfo(EmsOpenemsComponentRelation.class);
        initTableInfo(EmsOpenemsAppInstance.class);
        initTableInfo(EmsOpenemsAppComponent.class);
        initTableInfo(EmsOpenemsAppRelation.class);
        initTableInfo(EmsOpenemsEdgeCreateTask.class);
    }

    private static void initTableInfo(Class<?> entityType)
    {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
    }

    @Mock
    private EmsServerEndpointMapper endpointMapper;
    @Mock
    private EmsOpenemsEdgeMapper edgeMapper;
    @Mock
    private EmsOpenemsEdgeCreateTaskMapper createTaskMapper;
    @Mock
    private EmsOpenemsComponentMapper componentMapper;
    @Mock
    private EmsOpenemsComponentRelationMapper componentRelationMapper;
    @Mock
    private EmsOpenemsAppInstanceMapper appInstanceMapper;
    @Mock
    private EmsOpenemsAppComponentMapper appComponentMapper;
    @Mock
    private EmsOpenemsAppRelationMapper appRelationMapper;
    @Mock
    private OpenemsJsonRpcClient openemsJsonRpcClient;
    @Mock
    private EmsOpenemsProvisionDispatchService provisionDispatchService;
    @Mock
    private EmsOpenemsCapabilityService capabilityService;
    @Mock
    private TransactionTemplate transactionTemplate;
    @InjectMocks
    private EmsOpenemsResourceSyncService service;

    @BeforeEach
    void setUp()
    {
        EmsServerEndpoint endpoint = new EmsServerEndpoint();
        endpoint.setId(10L);
        endpoint.setTenantId(9999L);
        endpoint.setScopeType("TENANT");
        endpoint.setEnabled("0");
        lenient().when(endpointMapper.selectList(any(Wrapper.class)))
                .thenReturn(Collections.singletonList(endpoint));
        lenient().when(edgeMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        lenient().when(createTaskMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        lenient().when(componentMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        lenient().when(appInstanceMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
        lenient().when(componentMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        lenient().when(appInstanceMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        lenient().when(provisionDispatchService.dispatchPendingCurrentTenant(any(), any())).thenReturn(0);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(org.mockito.Mockito.mock(TransactionStatus.class));
        });
        lenient().doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(org.mockito.Mockito.mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void unknownEdgeIsInsertedAndEmsCreatedSourceIsPreserved()
    {
        EmsOpenemsEdge existing = edge("edge10001", "EMS_CREATED", "ONLINE");
        when(edgeMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(existing));
        when(openemsJsonRpcClient.listEdges(10L)).thenReturn(Arrays.asList(
                edgeRow("edge10001", false, "existing"), edgeRow("edge10002", false, "new")));

        service.syncHeartbeatCurrentTenant();

        ArgumentCaptor<EmsOpenemsEdge> inserted = ArgumentCaptor.forClass(EmsOpenemsEdge.class);
        verify(edgeMapper).insert(inserted.capture());
        assertEquals("BACKEND_SYNCED", inserted.getValue().getSourceType());
        assertNull(inserted.getValue().getCompanyId());
        assertNull(inserted.getValue().getStationId());
        assertEquals("EMS_CREATED", existing.getSourceType());
        assertEquals("OFFLINE", existing.getOnlineStatus());
    }

    @Test
    void onlineTransitionImmediatelyRefreshesResources()
    {
        EmsOpenemsEdge existing = edge("edge10001", "EMS_CREATED", "OFFLINE");
        when(edgeMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(existing));
        when(openemsJsonRpcClient.listEdges(10L))
                .thenReturn(Collections.singletonList(edgeRow("edge10001", true, "existing")));
        when(openemsJsonRpcClient.getEdgeConfig(10L, "edge10001")).thenReturn(emptyConfig());
        when(openemsJsonRpcClient.getAppSnapshot(10L, "edge10001")).thenReturn(emptyApps());

        service.syncHeartbeatCurrentTenant();

        verify(openemsJsonRpcClient).getEdgeConfig(10L, "edge10001");
        verify(openemsJsonRpcClient).getAppSnapshot(10L, "edge10001");
    }

    @Test
    void onlineEdgeCapabilitiesAreRefreshedDuringResourceSync()
    {
        EmsOpenemsEdge existing = edge("edge10001", "EMS_CREATED", "ONLINE");
        when(edgeMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(existing));
        when(edgeMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(openemsJsonRpcClient.listEdges(10L))
                .thenReturn(Collections.singletonList(edgeRow("edge10001", true, "existing")));
        when(openemsJsonRpcClient.getEdgeConfig(10L, "edge10001")).thenReturn(emptyConfig());
        when(openemsJsonRpcClient.getAppSnapshot(10L, "edge10001")).thenReturn(emptyApps());

        service.syncFullCurrentTenant();

        verify(capabilityService).refresh(existing.getId());
    }

    @Test
    void offlineEdgeDoesNotReadEdgeConfiguration()
    {
        when(openemsJsonRpcClient.listEdges(10L))
                .thenReturn(Collections.singletonList(edgeRow("edge10001", false, "offline")));

        service.syncFullCurrentTenant();

        verify(openemsJsonRpcClient, never()).getEdgeConfig(any(), any());
        verify(openemsJsonRpcClient, never()).getAppSnapshot(any(), any());
    }

    @Test
    void emptyBackendResultDoesNotMarkResourcesMissing()
    {
        when(openemsJsonRpcClient.listEdges(10L)).thenReturn(Collections.emptyList());

        service.syncFullCurrentTenant();

        verify(componentMapper, never()).update(any(), any(Wrapper.class));
        verify(appInstanceMapper, never()).update(any(), any(Wrapper.class));
        verify(openemsJsonRpcClient, never()).getEdgeConfig(any(), any());
    }

    @Test
    void nonNumericEdgeIdKeepsMetadataButDisablesStandardTimeseries()
    {
        when(openemsJsonRpcClient.listEdges(10L))
                .thenReturn(Collections.singletonList(edgeRow("plant-alpha", false, "legacy")));

        service.syncHeartbeatCurrentTenant();

        ArgumentCaptor<EmsOpenemsEdge> inserted = ArgumentCaptor.forClass(EmsOpenemsEdge.class);
        verify(edgeMapper).insert(inserted.capture());
        assertNull(inserted.getValue().getEdgeKey());
        assertEquals("TIMESERIES_UNAVAILABLE_EDGE_ID_FORMAT", inserted.getValue().getDataCapabilityStatus());
    }

    @Test
    void successfulSnapshotsOnlyMarkActuallyMissingComponentsAndApps()
    {
        EmsOpenemsEdge existing = edge("edge10001", "BACKEND_SYNCED", "ONLINE");
        when(edgeMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(existing));
        when(openemsJsonRpcClient.listEdges(10L))
                .thenReturn(Collections.singletonList(edgeRow("edge10001", true, "online")));
        when(openemsJsonRpcClient.getEdgeConfig(10L, "edge10001")).thenReturn(emptyConfig());
        when(openemsJsonRpcClient.getAppSnapshot(10L, "edge10001")).thenReturn(emptyApps());

        service.syncFullCurrentTenant();

        verify(componentMapper, atLeastOnce()).update(eq(null), any(Wrapper.class));
        verify(appInstanceMapper, atLeastOnce()).update(eq(null), any(Wrapper.class));
        verify(appComponentMapper, atLeastOnce()).update(eq(null), any(Wrapper.class));
        verify(appRelationMapper, atLeastOnce()).update(eq(null), any(Wrapper.class));
    }

    @Test
    void appReadFailureKeepsPreviousAppSnapshot()
    {
        EmsOpenemsEdge existing = edge("edge10001", "BACKEND_SYNCED", "ONLINE");
        when(edgeMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(existing));
        when(openemsJsonRpcClient.listEdges(10L))
                .thenReturn(Collections.singletonList(edgeRow("edge10001", true, "online")));
        when(openemsJsonRpcClient.getEdgeConfig(10L, "edge10001")).thenReturn(emptyConfig());
        when(openemsJsonRpcClient.getAppSnapshot(10L, "edge10001"))
                .thenThrow(new IllegalStateException("App Manager unavailable"));

        service.syncFullCurrentTenant();

        verify(appInstanceMapper, never()).update(any(), any(Wrapper.class));
        verify(appComponentMapper, never()).update(any(), any(Wrapper.class));
        verify(appRelationMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void commentReconciliationSucceedsAndTrustedFullMissAllowsRetry()
    {
        EmsOpenemsEdgeCreateTask matched = pendingTask(1L, "marker-found");
        EmsOpenemsEdgeCreateTask missing = pendingTask(2L, "marker-missing");
        when(createTaskMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(matched, missing));
        when(openemsJsonRpcClient.listEdges(10L))
                .thenReturn(Collections.singletonList(edgeRow("edge10001", false, "marker-found")));

        service.syncFullCurrentTenant();

        assertEquals("SUCCESS", matched.getState());
        assertEquals("edge10001", matched.getBackendEdgeId());
        assertEquals("FAILED", missing.getState());
        assertEquals("RECONCILIATION_NOT_FOUND", missing.getErrorCode());
    }

    @Test
    void deviceClassificationUsesExactOpenemsNatures()
    {
        JSONArray ess = JSONArray.parseArray("[\"io.openems.edge.ess.api.SymmetricEss\"]");
        JSONArray evcs = JSONArray.parseArray("[\"io.openems.edge.meter.api.ElectricityMeter\",\"io.openems.edge.evcs.api.Evcs\"]");
        JSONArray meter = JSONArray.parseArray("[\"io.openems.edge.meter.api.ElectricityMeter\"]");
        JSONArray essPower = JSONArray.parseArray("[\"io.openems.edge.ess.core.power.EssPower\",\"io.openems.edge.ess.power.api.Power\"]");
        JSONArray evcsPower = JSONArray.parseArray("[\"io.openems.edge.evcs.api.EvcsPower\"]");

        assertTrue(service.isDeviceComponent("Simulator.EssSymmetric.Reacting", ess));
        assertTrue(service.isDeviceComponent("Simulator.Evcs", evcs));
        assertTrue(service.isDeviceComponent("Simulator.ProductionMeter.Acting", meter));
        assertFalse(service.isDeviceComponent("Ess.Power", essPower));
        assertFalse(service.isDeviceComponent("Evcs.SlowPowerIncreaseFilter", evcsPower));
        assertEquals("ESS", service.deviceType("Simulator.EssSymmetric.Reacting", ess.toJSONString()));
        assertEquals("CHARGER", service.deviceType("Simulator.Evcs", evcs.toJSONString()));
        assertEquals("METER", service.deviceType("Simulator.ProductionMeter.Acting", meter.toJSONString()));
    }

    private EmsOpenemsEdge edge(String edgeId, String source, String status)
    {
        EmsOpenemsEdge edge = new EmsOpenemsEdge();
        edge.setId(1L);
        edge.setTenantId(9999L);
        edge.setEndpointId(10L);
        edge.setEdgeId(edgeId);
        edge.setSourceType(source);
        edge.setOnlineStatus(status);
        edge.setDelFlag("0");
        return edge;
    }

    private EmsOpenemsEdgeCreateTask pendingTask(Long id, String marker)
    {
        EmsOpenemsEdgeCreateTask task = new EmsOpenemsEdgeCreateTask();
        task.setId(id);
        task.setTenantId(9999L);
        task.setEndpointId(10L);
        task.setEdgeName("created-edge");
        task.setCommentMarker(marker);
        task.setState("PENDING_RECONCILIATION");
        return task;
    }

    private Map<String, Object> edgeRow(String edgeId, boolean online, String comment)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", edgeId);
        row.put("isOnline", online);
        row.put("comment", comment);
        return row;
    }

    private Map<String, Object> emptyConfig()
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("components", new LinkedHashMap<String, Object>());
        result.put("factories", new LinkedHashMap<String, Object>());
        return result;
    }

    private Map<String, Object> emptyApps()
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("apps", Collections.emptyList());
        result.put("instances", Collections.emptyList());
        return result;
    }
}
