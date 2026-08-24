package com.witos.ems.server.service.impl;

import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.domain.entity.EmsCompany;
import com.witos.ems.server.domain.entity.EmsDevice;
import com.witos.ems.server.domain.entity.EmsStation;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsDeviceMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsDeviceServiceImplTest
{
    @Mock
    private EmsDeviceMapper deviceMapper;
    @Mock
    private EmsCompanyMapper companyMapper;
    @Mock
    private EmsStationMapper stationMapper;
    @Mock
    private EmsAuthScopeService authScopeService;

    @InjectMocks
    private EmsDeviceServiceImpl service;

    @Test
    void createInheritsStationTenant()
    {
        Map<String, Object> companyDetail = new LinkedHashMap<String, Object>();
        companyDetail.put("companyId", 33L);
        companyDetail.put("tenantId", 1005L);
        when(companyMapper.selectCompanyDetail(eq(33L), any())).thenReturn(companyDetail);
        EmsCompany company = new EmsCompany();
        company.setId(33L);
        company.setTenantId(1005L);
        when(companyMapper.selectById(33L)).thenReturn(company);

        Map<String, Object> stationDetail = new LinkedHashMap<String, Object>();
        stationDetail.put("stationId", 44L);
        stationDetail.put("companyId", 33L);
        stationDetail.put("tenantId", 1005L);
        when(stationMapper.selectStationDetail(eq(44L), any())).thenReturn(stationDetail);
        EmsStation station = new EmsStation();
        station.setId(44L);
        station.setCompanyId(33L);
        station.setTenantId(1005L);
        when(stationMapper.selectById(44L)).thenReturn(station);
        doAnswer(invocation -> {
            EmsDevice device = invocation.getArgument(0);
            device.setId(55L);
            return 1;
        }).when(deviceMapper).insert(any(EmsDevice.class));
        when(deviceMapper.selectDeviceDetail(eq(55L), any())).thenReturn(new LinkedHashMap<String, Object>());

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("companyId", 33L);
        body.put("stationId", 44L);
        body.put("deviceName", "逆变器1");
        service.save(body, "INVERTER");

        ArgumentCaptor<EmsDevice> device = ArgumentCaptor.forClass(EmsDevice.class);
        verify(deviceMapper).insert(device.capture());
        assertEquals(1005L, device.getValue().getTenantId());
    }
}
