package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.domain.entity.EmsReportSyncTask;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsReportSyncTaskMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.ems.server.support.EmsBusinessParamTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsReportSyncTaskServiceImplTest
{
    @Mock
    private EmsReportSyncTaskMapper syncTaskMapper;

    @Mock
    private EmsCompanyMapper companyMapper;

    @Mock
    private EmsStationMapper stationMapper;

    @Mock
    private EmsAuthScopeService authScopeService;

    @Mock
    private EmsPriceResolver priceResolver;

    @Mock
    private EmsBusinessParamResolver businessParamResolver;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private EmsReportSyncTaskServiceImpl service;

    @BeforeEach
    void executeTransactionCallback()
    {
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Integer> callback = invocation.getArgument(0);
            return callback.doInTransaction(org.mockito.Mockito.mock(TransactionStatus.class));
        });
    }

    @Test
    void successfulAutomaticTaskIsSkipped()
    {
        EmsReportSyncTask task = task("SUCCESS", "station", "DAY");
        when(syncTaskMapper.selectOne(any(Wrapper.class))).thenReturn(task);
        when(syncTaskMapper.claimTask(eq(task.getId()), eq(task.getTenantId()), eq(false), eq(0), anyString(), any(Date.class)))
                .thenReturn(0);
        when(syncTaskMapper.selectById(task.getId())).thenReturn(task);

        Map<String, Object> result = service.startTask(body("station", "DAY"));

        assertEquals("SUCCESS", result.get("taskStatus"));
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void runningTaskCannotBeClaimedTwice()
    {
        EmsReportSyncTask task = task("RUNNING", "station", "DAY");
        when(syncTaskMapper.selectOne(any(Wrapper.class))).thenReturn(task);
        when(syncTaskMapper.claimTask(eq(task.getId()), eq(task.getTenantId()), eq(false), eq(0), anyString(), any(Date.class)))
                .thenReturn(0);
        when(syncTaskMapper.selectById(task.getId())).thenReturn(task);

        Map<String, Object> result = service.startTask(body("station", "DAY"));

        assertEquals("RUNNING", result.get("taskStatus"));
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void failedAutomaticTaskIsRetried()
    {
        EmsReportSyncTask task = task("FAILED", "inverter", "DAY");
        when(syncTaskMapper.selectOne(any(Wrapper.class))).thenReturn(task);
        when(syncTaskMapper.claimTask(eq(task.getId()), eq(task.getTenantId()), eq(false), eq(1), anyString(), any(Date.class)))
                .thenReturn(1);
        when(syncTaskMapper.insertDeviceAggregateRows(anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(1);
        when(syncTaskMapper.finishTask(eq(task.getId()), eq(task.getTenantId()), eq("SUCCESS"), eq(1), eq(null), anyString(), any(Date.class)))
                .thenReturn(1);
        when(syncTaskMapper.selectById(task.getId())).thenAnswer(invocation -> {
            task.setTaskStatus("SUCCESS");
            task.setRetryCount(1);
            return task;
        });

        Map<String, Object> result = service.startTask(body("inverter", "DAY"));

        assertEquals("SUCCESS", result.get("taskStatus"));
        verify(syncTaskMapper, never()).deleteDeviceAggregateRows(anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void rebuildForcesSuccessfulTaskAndDeletesRangeBeforeUpsert()
    {
        EmsReportSyncTask task = task("SUCCESS", "station", "DAY");
        when(syncTaskMapper.selectOne(any(Wrapper.class))).thenReturn(task);
        when(syncTaskMapper.claimTask(eq(task.getId()), eq(task.getTenantId()), eq(true), eq(1), anyString(), any(Date.class)))
                .thenReturn(1);
        when(syncTaskMapper.insertStationAggregateRows(anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(2);
        when(syncTaskMapper.finishTask(eq(task.getId()), eq(task.getTenantId()), eq("SUCCESS"), eq(2), eq(null), anyString(), any(Date.class)))
                .thenReturn(1);
        task.setTaskStatus("SUCCESS");
        when(syncTaskMapper.selectById(task.getId())).thenReturn(task);
        Map<String, Object> body = body("station", "DAY");
        body.put("rebuild", true);

        service.startTask(body);

        verify(syncTaskMapper).deleteStationAggregateRows(anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(syncTaskMapper).insertStationAggregateRows(anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void stationHourRevenueUsesReportTimestamp()
    {
        EmsReportSyncTask task = task("FAILED", "station", "HOUR");
        Date statTime = date("2026-01-02 03:00:00");
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", 99L);
        row.put("companyId", 10L);
        row.put("stationId", 20L);
        row.put("statTime", statTime);
        row.put("generationKwh", BigDecimal.TEN);
        when(syncTaskMapper.selectOne(any(Wrapper.class))).thenReturn(task);
        when(syncTaskMapper.claimTask(anyLong(), anyLong(), anyBoolean(), anyInt(), anyString(), any(Date.class))).thenReturn(1);
        when(syncTaskMapper.selectStationHourRows(anyLong(), anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(java.util.Collections.singletonList(row));
        EmsPriceResolver.RevenueBreakdown revenue = new EmsPriceResolver.RevenueBreakdown();
        revenue.setRevenueAmount(BigDecimal.ZERO);
        revenue.setFeedInRevenue(BigDecimal.ZERO);
        revenue.setSelfUseSaving(BigDecimal.ZERO);
        revenue.setStorageArbitrageRevenue(BigDecimal.ZERO);
        revenue.setPurchaseCost(BigDecimal.ZERO);
        revenue.setQualityReason("test");
        when(priceResolver.resolveRevenueBreakdown(eq(9999L), eq(10L), eq(20L), eq(BigDecimal.TEN), eq(BigDecimal.ZERO),
                eq(BigDecimal.ZERO), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO), eq(statTime), eq(true)))
            .thenReturn(revenue);
        when(businessParamResolver.resolveDecimal(any(EmsBusinessParamTemplate.class), anyLong(), anyLong(), anyLong()))
            .thenReturn(BigDecimal.ZERO);
        when(syncTaskMapper.updateStationHourDerivedValues(anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(syncTaskMapper.finishTask(anyLong(), anyLong(), eq("SUCCESS"), eq(1), eq(null), anyString(), any(Date.class)))
                .thenReturn(1);
        when(syncTaskMapper.selectById(task.getId())).thenAnswer(invocation -> {
            task.setTaskStatus("SUCCESS");
            return task;
        });

        service.startTask(body("station", "HOUR"));

        verify(priceResolver).resolveRevenueBreakdown(eq(9999L), eq(10L), eq(20L), eq(BigDecimal.TEN), eq(BigDecimal.ZERO),
                eq(BigDecimal.ZERO), eq(BigDecimal.ZERO), eq(BigDecimal.ZERO), eq(statTime), eq(true));
    }

    private EmsReportSyncTask task(String status, String reportType, String periodType)
    {
        EmsReportSyncTask task = new EmsReportSyncTask();
        task.setId(1L);
        task.setTenantId(9999L);
        task.setCompanyId(0L);
        task.setStationId(0L);
        task.setReportType(reportType);
        task.setPeriodType(periodType);
        task.setRangeStartTime(date("2026-01-01 00:00:00"));
        task.setRangeEndTime(date("2026-01-01 23:59:59"));
        task.setTaskKey("key");
        task.setTaskStatus(status);
        task.setRetryCount(0);
        task.setDelFlag("0");
        return task;
    }

    private Map<String, Object> body(String reportType, String periodType)
    {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("reportType", reportType);
        body.put("periodType", periodType);
        body.put("rangeStartTime", "2026-01-01 00:00:00");
        body.put("rangeEndTime", "2026-01-01 23:59:59");
        body.put("sourceSystem", "SCHEDULE");
        return body;
    }

    private Date date(String value)
    {
        try
        {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(value);
        }
        catch (Exception ex)
        {
            throw new IllegalArgumentException(ex);
        }
    }
}
