package com.witos.ems.server.schedule;

import com.witos.common.redis.service.RedisService;
import com.witos.ems.server.config.EmsScheduleProperties;
import com.witos.ems.server.mapper.EmsTenantScheduleMapper;
import com.witos.ems.server.openems.EmsOpenemsResourceSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsOpenemsScheduleTest
{
    @Mock
    private EmsTenantScheduleMapper tenantScheduleMapper;
    @Mock
    private EmsOpenemsResourceSyncService resourceSyncService;
    @Mock
    private RedisService redisService;
    @InjectMocks
    private EmsTenantScheduleExecutor tenantScheduleExecutor;

    @Test
    void scheduleSwitchControlsHeartbeatAndFullSync()
    {
        EmsScheduleProperties properties = new EmsScheduleProperties();
        EmsTenantScheduleExecutor executor = org.mockito.Mockito.mock(EmsTenantScheduleExecutor.class);
        EmsDataSchedule schedule = new EmsDataSchedule();
        ReflectionTestUtils.setField(schedule, "scheduleProperties", properties);
        ReflectionTestUtils.setField(schedule, "tenantScheduleExecutor", executor);

        schedule.openemsHeartbeat();
        schedule.openemsFullSync();
        verify(executor, never()).executeOpenemsHeartbeat();
        verify(executor, never()).executeOpenemsFullSync();

        properties.setEnabled(true);
        schedule.openemsHeartbeat();
        schedule.openemsFullSync();
        verify(executor).executeOpenemsHeartbeat();
        verify(executor).executeOpenemsFullSync();
    }

    @Test
    void heartbeatUsesDistributedTenantLock()
    {
        when(tenantScheduleMapper.selectEnabledTenantIds()).thenReturn(Collections.singletonList(1001L));
        when(redisService.setCacheObjectIfAbsent(anyString(), anyString(), eq(2L), eq(TimeUnit.MINUTES)))
                .thenReturn(true);

        tenantScheduleExecutor.executeOpenemsHeartbeat();

        verify(resourceSyncService).syncHeartbeatCurrentTenant();
        verify(redisService).compareAndDelete(eq("ems:schedule:openems-resource-sync:1001"), anyString());
    }

    @Test
    void fullSyncSkipsWhenAnotherInstanceOwnsLock()
    {
        when(tenantScheduleMapper.selectEnabledTenantIds()).thenReturn(Collections.singletonList(1001L));
        when(redisService.setCacheObjectIfAbsent(anyString(), anyString(), eq(10L), eq(TimeUnit.MINUTES)))
                .thenReturn(false);

        tenantScheduleExecutor.executeOpenemsFullSync();

        verify(resourceSyncService, never()).syncFullCurrentTenant();
        verify(redisService, never()).compareAndDelete(anyString(), anyString());
    }
}
