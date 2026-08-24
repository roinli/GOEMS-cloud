package com.witos.ems.server.schedule;

import com.witos.common.mybatisplus.util.TenantUtils;
import com.witos.common.redis.service.RedisService;
import com.witos.ems.server.mapper.EmsTenantScheduleMapper;
import com.witos.ems.server.openems.EmsOpenemsHistorySyncService;
import com.witos.ems.server.openems.EmsOpenemsRealtimeSyncService;
import com.witos.ems.server.openems.EmsOpenemsResourceSyncService;
import com.witos.ems.server.service.EmsReportSyncTaskService;
import com.witos.ems.server.service.impl.EmsAlarmEvaluationService;
import com.witos.ems.server.service.impl.EmsDataRetentionService;
import com.witos.ems.server.support.EmsRequestSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class EmsTenantScheduleExecutor
{
    private static final List<String> DEVICE_REPORT_TYPES = Arrays.asList("inverter", "pcs", "storage", "meter");

    @Resource
    private EmsTenantScheduleMapper tenantScheduleMapper;

    @Resource
    private EmsOpenemsRealtimeSyncService realtimeSyncService;

    @Resource
    private EmsOpenemsHistorySyncService historySyncService;

    @Resource
    private EmsOpenemsResourceSyncService resourceSyncService;

    @Resource
    private RedisService redisService;

    @Resource
    private EmsReportSyncTaskService reportSyncTaskService;

    @Resource
    private EmsAlarmEvaluationService alarmEvaluationService;

    @Resource
    private EmsDataRetentionService dataRetentionService;

    public void executeRealtime()
    {
        forEachEnabledTenant("realtime", () -> realtimeSyncService.syncActiveBindings(null));
    }

    public void executeHistory(Date bucketTime)
    {
        forEachEnabledTenant("history:" + format(bucketTime), () -> historySyncService.syncBucket(bucketTime, null));
    }

    public void executeReport(String periodType, Date rangeStartTime, Date rangeEndTime)
    {
        forEachEnabledTenant("report:" + periodType + ":" + format(rangeStartTime),
                () -> executeReportForCurrentTenant(periodType, rangeStartTime, rangeEndTime, false));
    }

    public void executeAlarmEvaluation()
    {
        forEachEnabledTenant("alarm-evaluation", alarmEvaluationService::evaluateCurrentTenant);
    }

    public void executeOpenemsHeartbeat()
    {
        forEachEnabledTenantLocked("openems-heartbeat", 2L,
                resourceSyncService::syncHeartbeatCurrentTenant);
    }

    public void executeOpenemsFullSync()
    {
        forEachEnabledTenantLocked("openems-full-sync", 10L,
                resourceSyncService::syncFullCurrentTenant);
    }

    public void executeGapFill(Date rangeStartTime, Date rangeEndTime)
    {
        forEachEnabledTenant("gap-fill:" + format(rangeStartTime), () -> {
            List<Date> missingBuckets = historySyncService.findMissingBuckets(rangeStartTime, rangeEndTime, null);
            Set<Long> affectedHours = new LinkedHashSet<Long>();
            for (Date bucketTime : missingBuckets)
            {
                if (historySyncService.syncBucket(bucketTime, null) > 0)
                {
                    affectedHours.add(startOfHour(bucketTime).getTime());
                }
            }
            for (Long hour : affectedHours)
            {
                Date hourStart = new Date(hour);
                Date hourEnd = new Date(hour + 60 * 60 * 1000L - 1000L);
                executeReportForCurrentTenant("HOUR", hourStart, hourEnd, true);
            }
        });
    }

    public void executeRetentionCleanup()
    {
        forEachEnabledTenant("retention-cleanup", () -> {
            Map<String, Integer> deleted = dataRetentionService.cleanupCurrentTenant();
            log.info("EMS retention cleanup completed, tenantId={}, deleted={}",
                    EmsRequestSupport.currentTenantId(), deleted);
        });
    }

    private void executeReportForCurrentTenant(String periodType, Date rangeStartTime, Date rangeEndTime, boolean rebuild)
    {
        for (String reportType : DEVICE_REPORT_TYPES)
        {
            startOrRetryReport(reportType, periodType, rangeStartTime, rangeEndTime, rebuild);
        }
        startOrRetryReport("station", periodType, rangeStartTime, rangeEndTime, rebuild);
    }

    private void startOrRetryReport(String reportType, String periodType, Date rangeStartTime, Date rangeEndTime,
                                    boolean rebuild)
    {
        reportSyncTaskService.startTask(reportBody(reportType, periodType, rangeStartTime, rangeEndTime, rebuild));
    }

    private Map<String, Object> reportBody(String reportType, String periodType, Date rangeStartTime, Date rangeEndTime,
                                           boolean rebuild)
    {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("reportType", reportType);
        body.put("periodType", periodType);
        body.put("rangeStartTime", format(rangeStartTime));
        body.put("rangeEndTime", format(rangeEndTime));
        body.put("sourceSystem", "SCHEDULE");
        body.put("rebuild", rebuild);
        return body;
    }

    private Date startOfHour(Date value)
    {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTime(value);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    private void forEachEnabledTenant(String taskName, Runnable action)
    {
        List<Long> tenantIds = tenantScheduleMapper.selectEnabledTenantIds();
        for (Long tenantId : tenantIds)
        {
            try
            {
                TenantUtils.execute(tenantId, action);
            }
            catch (Exception ex)
            {
                log.error("EMS scheduled task failed, task={}, tenantId={}", taskName, tenantId, ex);
            }
        }
    }

    private void forEachEnabledTenantLocked(String taskName, long lockMinutes, Runnable action)
    {
        List<Long> tenantIds = tenantScheduleMapper.selectEnabledTenantIds();
        for (Long tenantId : tenantIds)
        {
            String lockKey = "ems:schedule:openems-resource-sync:" + tenantId;
            String lockToken = UUID.randomUUID().toString();
            boolean acquired;
            try
            {
                acquired = redisService.setCacheObjectIfAbsent(lockKey, lockToken, lockMinutes, TimeUnit.MINUTES);
            }
            catch (Exception ex)
            {
                log.error("EMS OpenEMS schedule lock unavailable, task={}, tenantId={}", taskName, tenantId, ex);
                continue;
            }
            if (!acquired)
            {
                log.info("EMS OpenEMS scheduled task skipped because another instance owns the lock, task={}, tenantId={}",
                        taskName, tenantId);
                continue;
            }
            try
            {
                TenantUtils.execute(tenantId, action);
            }
            catch (Exception ex)
            {
                log.error("EMS scheduled task failed, task={}, tenantId={}", taskName, tenantId, ex);
            }
            finally
            {
                try
                {
                    redisService.compareAndDelete(lockKey, lockToken);
                }
                catch (Exception ex)
                {
                    log.error("EMS OpenEMS schedule lock release failed, task={}, tenantId={}", taskName, tenantId, ex);
                }
            }
        }
    }

    private String format(Date value)
    {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(value);
    }
}
