package com.witos.ems.server.service.impl;

import com.witos.ems.server.config.EmsMetricProperties;
import com.witos.ems.server.mapper.EmsDataRetentionMapper;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class EmsDataRetentionService
{
    @Resource
    private EmsDataRetentionMapper retentionMapper;

    @Resource
    private EmsMetricProperties metricProperties;

    public Map<String, Integer> cleanupCurrentTenant()
    {
        Long tenantId = EmsRequestSupport.currentTenantId();
        Date detailBefore = daysAgo(metricProperties.getDetailRetentionDays());
        Date logBefore = daysAgo(metricProperties.getSyncLogRetentionDays());
        Date taskBefore = daysAgo(metricProperties.getTaskRetentionDays());
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        result.put("metricHistory", retentionMapper.deleteMetricHistoryBefore(tenantId, detailBefore));
        result.put("syncLogs", retentionMapper.deleteSyncLogsBefore(tenantId, logBefore));
        result.put("reportTasks", retentionMapper.deleteReportTasksBefore(tenantId, taskBefore));
        return result;
    }

    private Date daysAgo(int days)
    {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, -Math.max(days, 1));
        return calendar.getTime();
    }
}
