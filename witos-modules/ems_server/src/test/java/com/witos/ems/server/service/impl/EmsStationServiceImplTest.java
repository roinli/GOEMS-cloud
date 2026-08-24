package com.witos.ems.server.service.impl;

import com.witos.common.core.exception.ServiceException;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.domain.entity.EmsStation;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.ems.server.service.EmsPriceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsStationServiceImplTest
{
    @Mock
    private EmsStationMapper stationMapper;

    @Mock
    private EmsCompanyMapper companyMapper;

    @Mock
    private EmsAuthScopeService authScopeService;

    @Mock
    private EmsPriceService priceService;

    @InjectMocks
    private EmsStationServiceImpl service;

    @Test
    void createInitializesDefaultPriceBindings()
    {
        prepareValidCompany();
        doAnswer(invocation -> {
            EmsStation station = invocation.getArgument(0);
            station.setId(44L);
            return 1;
        }).when(stationMapper).insert(any(EmsStation.class));
        when(stationMapper.selectStationDetail(eq(44L), any())).thenReturn(stationDetail());

        Map<String, Object> result = service.save(stationBody());

        assertEquals(44L, result.get("stationId"));
        verify(priceService).initDefaultAppliesForStation(1005L, 33L, 44L);
    }

    @Test
    void missingDefaultPriceFailurePropagatesFromCreateChain()
    {
        prepareValidCompany();
        doAnswer(invocation -> {
            EmsStation station = invocation.getArgument(0);
            station.setId(44L);
            return 1;
        }).when(stationMapper).insert(any(EmsStation.class));
        doThrow(new ServiceException("未配置可用的购电默认电价"))
                .when(priceService).initDefaultAppliesForStation(1005L, 33L, 44L);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.save(stationBody()));

        assertEquals("未配置可用的购电默认电价", exception.getMessage());
        verify(stationMapper).insert(any(EmsStation.class));
    }

    @Test
    void createInheritsCompanyTenant()
    {
        prepareValidCompany();
        doAnswer(invocation -> {
            EmsStation station = invocation.getArgument(0);
            station.setId(44L);
            return 1;
        }).when(stationMapper).insert(any(EmsStation.class));
        when(stationMapper.selectStationDetail(eq(44L), any())).thenReturn(stationDetail());

        service.save(stationBody());

        ArgumentCaptor<EmsStation> station = ArgumentCaptor.forClass(EmsStation.class);
        verify(stationMapper).insert(station.capture());
        assertEquals(1005L, station.getValue().getTenantId());
    }

    private void prepareValidCompany()
    {
        Map<String, Object> company = new LinkedHashMap<String, Object>();
        company.put("companyId", 33L);
        company.put("tenantId", 1005L);
        when(companyMapper.selectCompanyDetail(eq(33L), any())).thenReturn(company);
    }

    private Map<String, Object> stationBody()
    {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("companyId", 33L);
        body.put("stationName", "测试电站");
        return body;
    }

    private Map<String, Object> stationDetail()
    {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("stationId", 44L);
        detail.put("tenantId", 1005L);
        return detail;
    }
}
