package com.witos.ems.server.service.impl;

import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.mapper.EmsAlarmEventMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsAlarmServiceImplTest
{
    @Mock
    private EmsAlarmEventMapper alarmEventMapper;

    @Mock
    private EmsAuthScopeService authScopeService;

    @InjectMocks
    private EmsAlarmServiceImpl service;

    @Test
    void rootAckUsesEventTenant()
    {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("tenantId", 1005L);
        when(alarmEventMapper.selectAlarmEventDetail(eq(7L), any())).thenReturn(detail);
        when(alarmEventMapper.ackAlarmEvent(eq(7L), eq(1005L), any())).thenReturn(1);

        assertTrue(service.ack(7L));

        verify(alarmEventMapper).ackAlarmEvent(eq(7L), eq(1005L), any());
    }

    @Test
    void rootClearUsesEventTenant()
    {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("tenantId", 1005L);
        when(alarmEventMapper.selectAlarmEventDetail(eq(8L), any())).thenReturn(detail);
        when(alarmEventMapper.clearAlarmEvent(eq(8L), eq(1005L), any(), eq("MANUAL"))).thenReturn(1);

        assertTrue(service.clear(8L));

        verify(alarmEventMapper).clearAlarmEvent(eq(8L), eq(1005L), any(), eq("MANUAL"));
    }
}
