package com.witos.ems.server.schedule;

import com.witos.common.core.context.SecurityContextHolder;
import com.witos.ems.server.config.EmsScheduleProperties;
import com.witos.ems.server.config.EmsSchedulingConfiguration;
import com.witos.ems.server.mapper.EmsTenantScheduleMapper;
import com.witos.ems.server.openems.EmsOpenemsHistorySyncService;
import com.witos.ems.server.openems.EmsOpenemsRealtimeSyncService;
import com.witos.ems.server.service.EmsReportSyncTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsScheduleTest
{
    @Mock
    private EmsTenantScheduleMapper tenantScheduleMapper;

    @Mock
    private EmsOpenemsRealtimeSyncService realtimeSyncService;

    @Mock
    private EmsOpenemsHistorySyncService historySyncService;

    @Mock
    private EmsReportSyncTaskService reportSyncTaskService;

    @InjectMocks
    private EmsTenantScheduleExecutor tenantScheduleExecutor;

    @Test
    void disabledSwitchSkipsScheduledWork()
    {
        EmsScheduleProperties properties = new EmsScheduleProperties();
        EmsTenantScheduleExecutor executor = org.mockito.Mockito.mock(EmsTenantScheduleExecutor.class);
        EmsDataSchedule schedule = new EmsDataSchedule();
        ReflectionTestUtils.setField(schedule, "scheduleProperties", properties);
        ReflectionTestUtils.setField(schedule, "tenantScheduleExecutor", executor);

        schedule.realtime();
        verify(executor, never()).executeRealtime();

        properties.setEnabled(true);
        schedule.realtime();
        verify(executor).executeRealtime();
    }

    @Test
    void schedulerUsesFourThreads()
    {
        ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) new EmsSchedulingConfiguration().emsTaskScheduler();
        assertEquals(4, scheduler.getPoolSize());
    }

    @Test
    void tenantFailureContinuesAndContextIsRestored()
    {
        SecurityContextHolder.setTenantId("777");
        when(tenantScheduleMapper.selectEnabledTenantIds()).thenReturn(Arrays.asList(1001L, 1002L));
        doAnswer(invocation -> {
            if (Long.valueOf(1001L).equals(SecurityContextHolder.getTenantId()))
            {
                throw new IllegalStateException("first tenant failed");
            }
            assertEquals(Long.valueOf(1002L), SecurityContextHolder.getTenantId());
            return 0;
        }).when(realtimeSyncService).syncActiveBindings(null);

        tenantScheduleExecutor.executeRealtime();

        verify(realtimeSyncService, times(2)).syncActiveBindings(null);
        assertEquals(Long.valueOf(777L), SecurityContextHolder.getTenantId());
    }

    @Test
    void deviceReportsRunBeforeStationReport()
    {
        when(tenantScheduleMapper.selectEnabledTenantIds()).thenReturn(Collections.singletonList(1001L));
        Map<String, Object> success = new LinkedHashMap<String, Object>();
        success.put("taskStatus", "SUCCESS");
        when(reportSyncTaskService.startTask(any())).thenReturn(success);

        tenantScheduleExecutor.executeReport("HOUR", new Date(0), new Date(3599000));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(reportSyncTaskService, times(5)).startTask(captor.capture());
        List<Map<String, Object>> calls = captor.getAllValues();
        assertEquals("inverter", calls.get(0).get("reportType"));
        assertEquals("pcs", calls.get(1).get("reportType"));
        assertEquals("storage", calls.get(2).get("reportType"));
        assertEquals("meter", calls.get(3).get("reportType"));
        assertEquals("station", calls.get(4).get("reportType"));
        for (Map<String, Object> call : calls)
        {
            assertEquals("SCHEDULE", call.get("sourceSystem"));
        }
        verify(reportSyncTaskService, never()).retryTask(any());
    }
}
