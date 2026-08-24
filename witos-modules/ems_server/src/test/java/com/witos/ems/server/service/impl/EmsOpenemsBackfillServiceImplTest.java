package com.witos.ems.server.service.impl;

import com.witos.ems.server.domain.entity.EmsOpenemsBackfillTask;
import com.witos.ems.server.domain.entity.EmsOpenemsDevice;
import com.witos.ems.server.mapper.EmsOpenemsBackfillTaskMapper;
import com.witos.ems.server.mapper.EmsOpenemsDeviceMapper;
import com.witos.ems.server.mapper.EmsOpenemsResourceReportMapper;
import com.witos.ems.server.service.EmsOpenemsTimeseriesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsOpenemsBackfillServiceImplTest
{
    @Mock
    private EmsOpenemsBackfillTaskMapper taskMapper;
    @Mock
    private EmsOpenemsDeviceMapper deviceMapper;
    @Mock
    private EmsOpenemsTimeseriesService timeseriesService;
    @Mock
    private EmsOpenemsResourceReportMapper resourceReportMapper;
    @InjectMocks
    private EmsOpenemsBackfillServiceImpl service;

    @Test
    void rejectsRangeLongerThanOneYear()
    {
        when(deviceMapper.selectOne(any())).thenReturn(device("ACTIVE"));
        Map<String, Object> body = body("2024-01-01", "2025-01-02");
        assertThrows(RuntimeException.class, () -> service.create(1L, body));
        verify(taskMapper, never()).insert(any(EmsOpenemsBackfillTask.class));
    }

    @Test
    void rejectsOverlappingRange()
    {
        when(deviceMapper.selectOne(any())).thenReturn(device("ACTIVE"));
        EmsOpenemsBackfillTask existing = new EmsOpenemsBackfillTask();
        existing.setFromTime(date("2024-01-01"));
        existing.setToTime(date("2024-01-10"));
        existing.setState("RUNNING");
        when(taskMapper.selectList(any())).thenReturn(Collections.singletonList(existing));
        assertThrows(RuntimeException.class, () -> service.create(1L, body("2024-01-05", "2024-01-06")));
        verify(taskMapper, never()).insert(any(EmsOpenemsBackfillTask.class));
    }

    @Test
    void createsSingleTaskAndReadsInfluxWithoutWritingRawPoints()
    {
        when(deviceMapper.selectOne(any())).thenReturn(device("ACTIVE"));
        when(taskMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(taskMapper.insert(any(EmsOpenemsBackfillTask.class))).thenAnswer(invocation -> {
            EmsOpenemsBackfillTask task = invocation.getArgument(0);
            task.setId(99L);
            return 1;
        });
        Map<String, Object> history = new HashMap<String, Object>();
        history.put("source", "RAW");
        history.put("quality", "MISSING");
        history.put("rows", Collections.emptyList());
        when(timeseriesService.history(any(Long.class), any(Map.class))).thenReturn(history);
        Map<String, Object> result = service.create(1L, body("2024-01-01", "2024-01-02"));
        assertEquals("SUCCESS", result.get("state"));
        assertEquals("SUCCESS", result.get("reportRebuildState"));
        assertEquals("RAW", result.get("source"));
        verify(timeseriesService).history(any(Long.class), any(Map.class));
        verify(taskMapper).updateById(any(EmsOpenemsBackfillTask.class));
    }

    @Test
    void retriesFailedExactRangeWithoutInsertingDuplicate()
    {
        when(deviceMapper.selectOne(any())).thenReturn(device("ACTIVE"));
        EmsOpenemsBackfillTask existing = new EmsOpenemsBackfillTask();
        existing.setId(42L);
        existing.setTenantId(9999L);
        existing.setDeviceId(1L);
        existing.setFromTime(date("2024-01-01"));
        existing.setToTime(date("2024-01-02"));
        existing.setState("FAILED");
        existing.setDelFlag("0");
        when(taskMapper.selectOne(any())).thenReturn(existing);
        when(taskMapper.selectList(any())).thenReturn(Collections.emptyList());
        Map<String, Object> history = new HashMap<String, Object>();
        history.put("source", "RAW");
        history.put("quality", "GOOD");
        history.put("rows", Collections.emptyList());
        when(timeseriesService.history(any(Long.class), any(Map.class))).thenReturn(history);

        Map<String, Object> result = service.create(1L, body("2024-01-01", "2024-01-02"));

        assertEquals(42L, result.get("id"));
        assertEquals("SUCCESS", result.get("state"));
        verify(taskMapper, never()).insert(any(EmsOpenemsBackfillTask.class));
        verify(taskMapper).updateById(existing);
    }

    private Map<String, Object> body(String from, String to)
    {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("from", from);
        body.put("to", to);
        body.put("channels", "meter0/ActivePower");
        body.put("intervalSeconds", "3600");
        return body;
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

    private java.util.Date date(String value)
    {
        try { return new java.text.SimpleDateFormat("yyyy-MM-dd").parse(value); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
