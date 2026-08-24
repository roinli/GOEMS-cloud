package com.witos.ems.server.service.impl;

import com.witos.common.core.exception.ServiceException;
import com.witos.ems.server.domain.entity.EmsOpenemsDevice;
import com.witos.ems.server.domain.entity.EmsOpenemsProvisionTask;
import com.witos.ems.server.mapper.EmsOpenemsDeviceMapper;
import com.witos.ems.server.mapper.EmsOpenemsProvisionTaskMapper;
import com.witos.ems.server.service.EmsOpenemsTimeseriesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsOpenemsDeviceLifecycleServiceImplTest
{
    @Mock
    private EmsOpenemsDeviceMapper deviceMapper;
    @Mock
    private EmsOpenemsProvisionTaskMapper provisionTaskMapper;
    @Mock
    private EmsOpenemsTimeseriesService timeseriesService;
    @InjectMocks
    private EmsOpenemsDeviceLifecycleServiceImpl service;

    @Test
    void disablePreservesOpenemsAndInfluxAndStopsEmsProcessing()
    {
        EmsOpenemsDevice device = device("ACTIVE");
        when(deviceMapper.selectOne(any())).thenReturn(device);
        Map<String, Object> result = service.disable(1L);
        assertEquals("DISABLED", device.getStatus());
        assertFalse((Boolean) result.get("openemsComponentDeleted"));
        assertFalse((Boolean) result.get("influxHistoryDeleted"));
        assertFalse((Boolean) result.get("monitoringEnabled"));
        assertFalse((Boolean) result.get("revenueCalculationEnabled"));
        verify(deviceMapper).updateById(device);
    }

    @Test
    void enableResumesFromNowAndLeavesBackfillManual()
    {
        EmsOpenemsDevice device = device("DISABLED");
        Date disabledAt = new Date(System.currentTimeMillis() - 1000L);
        device.setDisabledAt(disabledAt);
        when(deviceMapper.selectOne(any())).thenReturn(device);
        Map<String, Object> result = service.enable(1L);
        assertEquals("ACTIVE", device.getStatus());
        assertEquals(Boolean.FALSE, result.get("historyAutoRecovered"));
        assertEquals(Boolean.TRUE, result.get("backfillAvailable"));
        verify(deviceMapper).updateById(device);
    }

    @Test
    void resourceReportMarksUnboundRevenueNotApplicable()
    {
        EmsOpenemsDevice device = device("ACTIVE");
        when(deviceMapper.selectOne(any())).thenReturn(device);
        Map<String, Object> history = new HashMap<String, Object>();
        history.put("source", "RAW");
        history.put("quality", "GOOD");
        history.put("rows", Collections.emptyList());
        when(timeseriesService.history(any(Long.class), any(Map.class))).thenReturn(history);
        Map<String, Object> result = service.resourceReport(1L, Collections.emptyMap());
        assertEquals("NOT_APPLICABLE", result.get("revenueStatus"));
        assertEquals(Boolean.FALSE, result.get("includedInCompanyStationSummary"));
        assertEquals(null, result.get("revenueAmount"));
    }

    @Test
    void failedDeviceWithoutEdgeSideEffectsCanBeRemoved()
    {
        EmsOpenemsDevice device = device("FAILED");
        device.setPrimaryComponentId("meterLoad0");
        EmsOpenemsProvisionTask task = task("FAILED");
        when(deviceMapper.selectOne(any())).thenReturn(device);
        when(provisionTaskMapper.selectOne(any())).thenReturn(task);
        when(provisionTaskMapper.delete(any())).thenReturn(1);
        when(deviceMapper.deleteById(1L)).thenReturn(1);

        Map<String, Object> result = service.removeFailed(1L);

        assertEquals(Boolean.TRUE, result.get("localOnly"));
        assertEquals(Boolean.FALSE, result.get("openemsComponentDeleted"));
        assertEquals(1, result.get("deletedProvisionTasks"));
        verify(deviceMapper).deleteById(1L);
    }

    @Test
    void disabledDeviceThatWasNeverCreatedOnEdgeCanBeRemoved()
    {
        EmsOpenemsDevice device = device("DISABLED");
        device.setPrimaryComponentId(null);
        EmsOpenemsProvisionTask task = task("DISABLED");
        when(deviceMapper.selectOne(any())).thenReturn(device);
        when(provisionTaskMapper.selectOne(any())).thenReturn(task);
        when(provisionTaskMapper.delete(any())).thenReturn(1);
        when(deviceMapper.deleteById(1L)).thenReturn(1);

        Map<String, Object> result = service.removeFailed(1L);

        assertEquals(Boolean.TRUE, result.get("localOnly"));
        assertEquals(1, result.get("deletedProvisionTasks"));
        verify(deviceMapper).deleteById(1L);
    }

    @Test
    void partialFailureCannotBeRemovedAsLocalOnly()
    {
        EmsOpenemsDevice device = device("FAILED");
        EmsOpenemsProvisionTask task = task("PARTIAL_FAILED");
        when(deviceMapper.selectOne(any())).thenReturn(device);
        when(provisionTaskMapper.selectOne(any())).thenReturn(task);

        ServiceException error = assertThrows(ServiceException.class, () -> service.removeFailed(1L));

        assertEquals("只有创建前失败且未落到Edge的设备可以删除", error.getMessage());
    }

    private EmsOpenemsDevice device(String status)
    {
        EmsOpenemsDevice device = new EmsOpenemsDevice();
        device.setId(1L);
        device.setTenantId(9999L);
        device.setEndpointId(10L);
        device.setEdgeId("edge1");
        device.setPrimaryComponentId("meter0");
        device.setStatus(status);
        device.setDelFlag("0");
        return device;
    }

    private EmsOpenemsProvisionTask task(String state)
    {
        EmsOpenemsProvisionTask task = new EmsOpenemsProvisionTask();
        task.setId(2L);
        task.setTenantId(9999L);
        task.setDeviceId(1L);
        task.setState(state);
        task.setDelFlag("0");
        return task;
    }
}
