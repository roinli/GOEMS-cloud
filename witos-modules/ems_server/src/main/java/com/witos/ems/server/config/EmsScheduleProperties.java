package com.witos.ems.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ems.schedule")
public class EmsScheduleProperties
{
    private boolean enabled = false;

    private String realtimeCron = "0 * * * * ?";

    private String openemsHeartbeatCron = "15 * * * * ?";

    private String openemsFullSyncCron = "45 0/5 * * * ?";

    private String historyCron = "0 2/5 * * * ?";

    private String hourlyReportCron = "0 10 * * * ?";

    private String dailyReportCron = "0 20 0 * * ?";

    private String monthlyReportCron = "0 40 0 1 * ?";

    private String yearlyReportCron = "0 10 1 1 1 ?";

    private String gapFillCron = "0 35 * * * ?";

    private String lateRepairCron = "0 30 2 * * ?";

    private String retentionCron = "0 30 3 * * ?";

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public String getRealtimeCron()
    {
        return realtimeCron;
    }

    public void setRealtimeCron(String realtimeCron)
    {
        this.realtimeCron = realtimeCron;
    }

    public String getOpenemsHeartbeatCron()
    {
        return openemsHeartbeatCron;
    }

    public void setOpenemsHeartbeatCron(String openemsHeartbeatCron)
    {
        this.openemsHeartbeatCron = openemsHeartbeatCron;
    }

    public String getOpenemsFullSyncCron()
    {
        return openemsFullSyncCron;
    }

    public void setOpenemsFullSyncCron(String openemsFullSyncCron)
    {
        this.openemsFullSyncCron = openemsFullSyncCron;
    }

    public String getHistoryCron()
    {
        return historyCron;
    }

    public void setHistoryCron(String historyCron)
    {
        this.historyCron = historyCron;
    }

    public String getHourlyReportCron()
    {
        return hourlyReportCron;
    }

    public void setHourlyReportCron(String hourlyReportCron)
    {
        this.hourlyReportCron = hourlyReportCron;
    }

    public String getDailyReportCron()
    {
        return dailyReportCron;
    }

    public void setDailyReportCron(String dailyReportCron)
    {
        this.dailyReportCron = dailyReportCron;
    }

    public String getMonthlyReportCron()
    {
        return monthlyReportCron;
    }

    public void setMonthlyReportCron(String monthlyReportCron)
    {
        this.monthlyReportCron = monthlyReportCron;
    }

    public String getYearlyReportCron()
    {
        return yearlyReportCron;
    }

    public void setYearlyReportCron(String yearlyReportCron)
    {
        this.yearlyReportCron = yearlyReportCron;
    }

    public String getGapFillCron()
    {
        return gapFillCron;
    }

    public void setGapFillCron(String gapFillCron)
    {
        this.gapFillCron = gapFillCron;
    }

    public String getLateRepairCron()
    {
        return lateRepairCron;
    }

    public void setLateRepairCron(String lateRepairCron)
    {
        this.lateRepairCron = lateRepairCron;
    }

    public String getRetentionCron()
    {
        return retentionCron;
    }

    public void setRetentionCron(String retentionCron)
    {
        this.retentionCron = retentionCron;
    }
}
