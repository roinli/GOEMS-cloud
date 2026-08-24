package com.witos.ems.server.schedule;

import com.witos.ems.server.config.EmsScheduleProperties;
import com.witos.ems.server.config.EmsMetricProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Calendar;
import java.util.Date;

@Slf4j
@Component
public class EmsDataSchedule
{
    @Resource
    private EmsScheduleProperties scheduleProperties;

    @Resource
    private EmsTenantScheduleExecutor tenantScheduleExecutor;

    @Resource
    private EmsMetricProperties metricProperties;

    @Scheduled(cron = "${ems.schedule.realtime-cron:0 * * * * ?}")
    public void realtime()
    {
        runIfEnabled("realtime", tenantScheduleExecutor::executeRealtime);
    }

    @Scheduled(cron = "${ems.schedule.openems-heartbeat-cron:15 * * * * ?}")
    public void openemsHeartbeat()
    {
        runIfEnabled("openems-heartbeat", tenantScheduleExecutor::executeOpenemsHeartbeat);
    }

    @Scheduled(cron = "${ems.schedule.openems-full-sync-cron:45 0/5 * * * ?}")
    public void openemsFullSync()
    {
        runIfEnabled("openems-full-sync", tenantScheduleExecutor::executeOpenemsFullSync);
    }

    @Scheduled(cron = "${ems.schedule.history-cron:0 2/5 * * * ?}")
    public void history()
    {
        Date bucketTime = floorToFiveMinutes(addMinutes(new Date(), -10));
        runIfEnabled("history", () -> tenantScheduleExecutor.executeHistory(bucketTime));
    }

    @Scheduled(cron = "${ems.schedule.hourly-report-cron:0 10 * * * ?}")
    public void hourlyReport()
    {
        Date end = startOfHour(new Date());
        Date start = addHours(end, -1);
        runIfEnabled("hourly-report", () -> tenantScheduleExecutor.executeReport("HOUR", start, addSeconds(end, -1)));
    }

    @Scheduled(cron = "${ems.schedule.alarm-cron:30 * * * * ?}")
    public void alarmEvaluation()
    {
        runIfEnabled("alarm-evaluation", tenantScheduleExecutor::executeAlarmEvaluation);
    }

    @Scheduled(cron = "${ems.schedule.daily-report-cron:0 20 0 * * ?}")
    public void dailyReport()
    {
        Date end = startOfDay(new Date());
        Date start = addDays(end, -1);
        runIfEnabled("daily-report", () -> tenantScheduleExecutor.executeReport("DAY", start, addSeconds(end, -1)));
    }

    @Scheduled(cron = "${ems.schedule.monthly-report-cron:0 40 0 1 * ?}")
    public void monthlyReport()
    {
        Date end = startOfMonth(new Date());
        Date start = addMonths(end, -1);
        runIfEnabled("monthly-report", () -> tenantScheduleExecutor.executeReport("MONTH", start, addSeconds(end, -1)));
    }

    @Scheduled(cron = "${ems.schedule.yearly-report-cron:0 10 1 1 1 ?}")
    public void yearlyReport()
    {
        Date end = startOfYear(new Date());
        Date start = addYears(end, -1);
        runIfEnabled("yearly-report", () -> tenantScheduleExecutor.executeReport("YEAR", start, addSeconds(end, -1)));
    }

    @Scheduled(cron = "${ems.schedule.gap-fill-cron:0 35 * * * ?}")
    public void gapFill()
    {
        Date end = startOfHour(new Date());
        Date start = addHours(end, -Math.max(metricProperties.getRepairLookbackHours(), 1));
        runIfEnabled("gap-fill", () -> tenantScheduleExecutor.executeGapFill(start, end));
    }

    @Scheduled(cron = "${ems.schedule.late-repair-cron:0 30 2 * * ?}")
    public void lateRepair()
    {
        Date end = startOfDay(new Date());
        Date start = addDays(end, -Math.max(metricProperties.getLateRepairDays(), 1));
        runIfEnabled("late-repair", () -> tenantScheduleExecutor.executeGapFill(start, end));
    }

    @Scheduled(cron = "${ems.schedule.retention-cron:0 30 3 * * ?}")
    public void retentionCleanup()
    {
        runIfEnabled("retention-cleanup", tenantScheduleExecutor::executeRetentionCleanup);
    }

    private void runIfEnabled(String taskName, Runnable action)
    {
        if (!scheduleProperties.isEnabled())
        {
            return;
        }
        long startedAt = System.currentTimeMillis();
        log.info("EMS scheduled task started, task={}", taskName);
        try
        {
            action.run();
            log.info("EMS scheduled task completed, task={}, durationMs={}", taskName, System.currentTimeMillis() - startedAt);
        }
        catch (Exception ex)
        {
            log.error("EMS scheduled task failed before tenant isolation, task={}", taskName, ex);
        }
    }

    private static Date floorToFiveMinutes(Date value)
    {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(value);
        calendar.set(Calendar.MINUTE, calendar.get(Calendar.MINUTE) - calendar.get(Calendar.MINUTE) % 5);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    private static Date startOfHour(Date value)
    {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(value);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    private static Date startOfDay(Date value)
    {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(value);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    private static Date startOfMonth(Date value)
    {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(startOfDay(value));
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        return calendar.getTime();
    }

    private static Date startOfYear(Date value)
    {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(startOfMonth(value));
        calendar.set(Calendar.MONTH, Calendar.JANUARY);
        return calendar.getTime();
    }

    private static Date addSeconds(Date value, int amount)
    {
        return add(value, Calendar.SECOND, amount);
    }

    private static Date addMinutes(Date value, int amount)
    {
        return add(value, Calendar.MINUTE, amount);
    }

    private static Date addHours(Date value, int amount)
    {
        return add(value, Calendar.HOUR_OF_DAY, amount);
    }

    private static Date addDays(Date value, int amount)
    {
        return add(value, Calendar.DAY_OF_MONTH, amount);
    }

    private static Date addMonths(Date value, int amount)
    {
        return add(value, Calendar.MONTH, amount);
    }

    private static Date addYears(Date value, int amount)
    {
        return add(value, Calendar.YEAR, amount);
    }

    private static Date add(Date value, int field, int amount)
    {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(value);
        calendar.add(field, amount);
        return calendar.getTime();
    }
}
