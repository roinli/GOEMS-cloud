package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.witos.common.core.exception.ServiceException;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.domain.entity.EmsCompany;
import com.witos.ems.server.domain.entity.EmsOpenemsBinding;
import com.witos.ems.server.domain.entity.EmsOpenemsBindingHistory;
import com.witos.ems.server.domain.entity.EmsOpenemsDevice;
import com.witos.ems.server.domain.entity.EmsOpenemsEdge;
import com.witos.ems.server.domain.entity.EmsStation;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsOpenemsBindingHistoryMapper;
import com.witos.ems.server.mapper.EmsOpenemsBindingMapper;
import com.witos.ems.server.mapper.EmsOpenemsBackfillTaskMapper;
import com.witos.ems.server.mapper.EmsOpenemsDeviceMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeMapper;
import com.witos.ems.server.mapper.EmsOpenemsProvisionTaskMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsOpenemsBindingServiceImplTest
{
    @BeforeAll
    static void initializeLambdaMetadata()
    {
        initTableInfo(EmsOpenemsEdge.class);
        initTableInfo(EmsOpenemsDevice.class);
        initTableInfo(EmsOpenemsBinding.class);
        initTableInfo(EmsOpenemsBindingHistory.class);
        initTableInfo(EmsCompany.class);
        initTableInfo(EmsStation.class);
    }

    private static void initTableInfo(Class<?> entityType)
    {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
    }

    @Mock
    private EmsOpenemsEdgeMapper edgeMapper;
    @Mock
    private EmsOpenemsDeviceMapper deviceMapper;
    @Mock
    private EmsOpenemsBindingMapper bindingMapper;
    @Mock
    private EmsOpenemsBindingHistoryMapper historyMapper;
    @Mock
    private EmsCompanyMapper companyMapper;
    @Mock
    private EmsStationMapper stationMapper;
    @Mock
    private EmsOpenemsProvisionTaskMapper provisionTaskMapper;
    @Mock
    private EmsOpenemsBackfillTaskMapper backfillTaskMapper;
    @Mock
    private EmsAuthScopeService authScopeService;
    @InjectMocks
    private EmsOpenemsBindingServiceImpl service;

    private EmsOpenemsEdge edge;
    private EmsCompany company;
    private EmsStation station;
    private final AtomicLong ids = new AtomicLong(100L);

    @BeforeEach
    void setUp()
    {
        edge = edge(1L, null, null);
        company = new EmsCompany();
        company.setId(100L);
        company.setTenantId(9999L);
        company.setDelFlag("0");
        station = new EmsStation();
        station.setId(1000L);
        station.setCompanyId(100L);
        station.setTenantId(9999L);
        station.setDelFlag("0");
        lenient().when(bindingMapper.insert(any(EmsOpenemsBinding.class))).thenAnswer(invocation -> {
            EmsOpenemsBinding binding = invocation.getArgument(0);
            binding.setId(ids.incrementAndGet());
            return 1;
        });
    }

    @Test
    void edgeInitialBindingInheritsExistingUnboundDevices()
    {
        EmsOpenemsDevice device = device(11L, null, null);
        when(edgeMapper.selectOne(any())).thenReturn(edge);
        when(companyMapper.selectOne(any())).thenReturn(company);
        when(stationMapper.selectOne(any())).thenReturn(station);
        when(deviceMapper.selectList(any())).thenReturn(Collections.singletonList(device));
        when(bindingMapper.selectList(any())).thenReturn(Collections.emptyList(), Collections.emptyList());

        Map<String, Object> result = service.bindEdge(1L, request(100L, 1000L, null));

        assertEquals(100L, edge.getCompanyId());
        assertEquals(1000L, edge.getStationId());
        assertEquals(100L, device.getCompanyId());
        assertEquals(1000L, device.getStationId());
        assertEquals(1, result.get("inheritedDeviceCount"));
        verify(bindingMapper, org.mockito.Mockito.times(2)).insert(any(EmsOpenemsBinding.class));
        verify(historyMapper, org.mockito.Mockito.times(2)).insert(any(EmsOpenemsBindingHistory.class));
    }

    @Test
    void rejectsStationThatDoesNotBelongToCompany()
    {
        station.setCompanyId(200L);
        when(edgeMapper.selectOne(any())).thenReturn(edge);
        when(companyMapper.selectOne(any())).thenReturn(company);
        when(stationMapper.selectOne(any())).thenReturn(station);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.bindEdge(1L, request(100L, 1000L, null)));

        assertEquals("电站不属于所选公司", error.getMessage());
        verify(bindingMapper, never()).insert(any(EmsOpenemsBinding.class));
    }

    @Test
    void rejectsDeviceBindingDifferentFromController()
    {
        edge.setCompanyId(100L);
        edge.setStationId(1000L);
        EmsOpenemsDevice device = device(11L, null, null);
        EmsCompany anotherCompany = new EmsCompany();
        anotherCompany.setId(200L);
        anotherCompany.setTenantId(9999L);
        EmsStation anotherStation = new EmsStation();
        anotherStation.setId(2000L);
        anotherStation.setCompanyId(200L);
        anotherStation.setTenantId(9999L);
        when(deviceMapper.selectOne(any())).thenReturn(device, device);
        when(edgeMapper.selectOne(any())).thenReturn(edge);
        when(companyMapper.selectOne(any())).thenReturn(anotherCompany);
        when(stationMapper.selectOne(any())).thenReturn(anotherStation);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.bindDevice(11L, request(200L, 2000L, null)));

        assertEquals("设备归属必须与控制器完全一致", error.getMessage());
        verify(bindingMapper, never()).insert(any(EmsOpenemsBinding.class));
    }

    @Test
    void movingEdgeClosesOldIntervalsAndWritesAuditHistory()
    {
        edge.setCompanyId(100L);
        edge.setStationId(1000L);
        EmsOpenemsDevice device = device(11L, 100L, 1000L);
        EmsCompany targetCompany = new EmsCompany();
        targetCompany.setId(200L);
        targetCompany.setTenantId(9999L);
        EmsStation targetStation = new EmsStation();
        targetStation.setId(2000L);
        targetStation.setCompanyId(200L);
        targetStation.setTenantId(9999L);
        Date from = truncate(new Date());
        EmsOpenemsBinding edgeBinding = binding(21L, "EDGE", 1L, 100L, 1000L, new Date(from.getTime() - 86400000L));
        EmsOpenemsBinding deviceBinding = binding(22L, "DEVICE", 11L, 100L, 1000L, new Date(from.getTime() - 86400000L));
        when(edgeMapper.selectOne(any())).thenReturn(edge);
        when(companyMapper.selectOne(any())).thenReturn(targetCompany);
        when(stationMapper.selectOne(any())).thenReturn(targetStation);
        when(deviceMapper.selectList(any())).thenReturn(Collections.singletonList(device));
        when(bindingMapper.selectList(any())).thenReturn(Collections.singletonList(edgeBinding), Collections.singletonList(deviceBinding));

        service.bindEdge(1L, request(200L, 2000L, from));

        assertEquals(from, edgeBinding.getEffectiveTo());
        assertEquals(from, deviceBinding.getEffectiveTo());
        assertEquals(100L, edgeBinding.getCompanyId());
        assertEquals(100L, deviceBinding.getCompanyId());
        ArgumentCaptor<EmsOpenemsBindingHistory> history = ArgumentCaptor.forClass(EmsOpenemsBindingHistory.class);
        verify(historyMapper, org.mockito.Mockito.times(2)).insert(history.capture());
        assertEquals("MOVE", history.getAllValues().get(0).getOperationType());
        assertEquals("INHERIT", history.getAllValues().get(1).getOperationType());
    }

    @Test
    void rejectsNewOpenIntervalWhenFutureIntervalAlreadyExists()
    {
        Date from = truncate(new Date());
        EmsOpenemsBinding future = binding(21L, "EDGE", 1L, 100L, 1000L, new Date(from.getTime() + 3600000L));
        future.setEffectiveTo(new Date(from.getTime() + 7200000L));
        when(edgeMapper.selectOne(any())).thenReturn(edge);
        when(companyMapper.selectOne(any())).thenReturn(company);
        when(stationMapper.selectOne(any())).thenReturn(station);
        when(deviceMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(bindingMapper.selectList(any())).thenReturn(Collections.singletonList(future));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.bindEdge(1L, request(100L, 1000L, from)));

        assertEquals("新的绑定区间与已有区间重叠", error.getMessage());
    }

    @Test
    void bindAllOnlyChangesUnboundDevices()
    {
        edge.setCompanyId(100L);
        edge.setStationId(1000L);
        EmsOpenemsDevice unbound = device(11L, null, null);
        EmsOpenemsDevice bound = device(12L, 100L, 1000L);
        when(edgeMapper.selectOne(any())).thenReturn(edge);
        when(deviceMapper.selectList(any())).thenReturn(Arrays.asList(unbound, bound));
        when(bindingMapper.selectList(any())).thenReturn(Collections.emptyList());

        Map<String, Object> result = service.bindAllUnboundDevices(1L, Collections.emptyMap());

        assertEquals(1, result.get("boundDeviceCount"));
        assertEquals(100L, unbound.getCompanyId());
        assertEquals(100L, bound.getCompanyId());
        verify(deviceMapper, org.mockito.Mockito.times(1)).updateById(any(EmsOpenemsDevice.class));
    }

    @Test
    void deviceUnbindClosesIntervalWithoutErasingHistoricalOwnership()
    {
        edge.setCompanyId(100L);
        edge.setStationId(1000L);
        EmsOpenemsDevice device = device(11L, 100L, 1000L);
        Date from = truncate(new Date());
        EmsOpenemsBinding current = binding(22L, "DEVICE", 11L, 100L, 1000L, new Date(from.getTime() - 3600000L));
        when(deviceMapper.selectOne(any())).thenReturn(device, device);
        when(edgeMapper.selectOne(any())).thenReturn(edge);
        when(bindingMapper.selectList(any())).thenReturn(Collections.singletonList(current));

        service.bindDevice(11L, request(null, null, from));

        assertNull(device.getCompanyId());
        assertNull(device.getStationId());
        assertEquals(100L, current.getCompanyId());
        assertEquals(1000L, current.getStationId());
        assertEquals(from, current.getEffectiveTo());
        verify(bindingMapper, never()).insert(any(EmsOpenemsBinding.class));
        ArgumentCaptor<EmsOpenemsBindingHistory> history = ArgumentCaptor.forClass(EmsOpenemsBindingHistory.class);
        verify(historyMapper).insert(history.capture());
        assertEquals("UNBIND", history.getValue().getOperationType());
    }

    private EmsOpenemsEdge edge(Long id, Long companyId, Long stationId)
    {
        EmsOpenemsEdge value = new EmsOpenemsEdge();
        value.setId(id);
        value.setTenantId(9999L);
        value.setEndpointId(10L);
        value.setEdgeId("edge0");
        value.setCompanyId(companyId);
        value.setStationId(stationId);
        value.setDelFlag("0");
        return value;
    }

    private EmsOpenemsDevice device(Long id, Long companyId, Long stationId)
    {
        EmsOpenemsDevice value = new EmsOpenemsDevice();
        value.setId(id);
        value.setTenantId(9999L);
        value.setEndpointId(10L);
        value.setEdgeId("edge0");
        value.setPrimaryComponentId("meter0");
        value.setCompanyId(companyId);
        value.setStationId(stationId);
        value.setDelFlag("0");
        return value;
    }

    private EmsOpenemsBinding binding(Long id, String type, Long resourceId, Long companyId, Long stationId, Date from)
    {
        EmsOpenemsBinding value = new EmsOpenemsBinding();
        value.setId(id);
        value.setTenantId(9999L);
        value.setResourceType(type);
        value.setResourceId(resourceId);
        value.setEndpointId(10L);
        value.setEdgeId("edge0");
        value.setCompanyId(companyId);
        value.setStationId(stationId);
        value.setEffectiveFrom(from);
        value.setStatus("ACTIVE");
        value.setDelFlag("0");
        return value;
    }

    private Map<String, Object> request(Long companyId, Long stationId, Date effectiveFrom)
    {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("companyId", companyId);
        body.put("stationId", stationId);
        if (effectiveFrom != null)
        {
            body.put("effectiveFrom", new Timestamp(effectiveFrom.getTime()));
        }
        return body;
    }

    private Date truncate(Date date)
    {
        return new Date((date.getTime() / 1000L) * 1000L);
    }
}
