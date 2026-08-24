package com.witos.ems.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ems.metric")
public class EmsMetricProperties
{
    private int detailRetentionDays = 7;

    private int repairLookbackHours = 2;

    private int lateRepairDays = 3;

    private int syncLogRetentionDays = 7;

    private int taskRetentionDays = 30;

    private String statisticsStartTime = "";

    public int getDetailRetentionDays()
    {
        return detailRetentionDays;
    }

    public void setDetailRetentionDays(int detailRetentionDays)
    {
        this.detailRetentionDays = detailRetentionDays;
    }

    public int getRepairLookbackHours()
    {
        return repairLookbackHours;
    }

    public void setRepairLookbackHours(int repairLookbackHours)
    {
        this.repairLookbackHours = repairLookbackHours;
    }

    public int getLateRepairDays()
    {
        return lateRepairDays;
    }

    public void setLateRepairDays(int lateRepairDays)
    {
        this.lateRepairDays = lateRepairDays;
    }

    public int getSyncLogRetentionDays()
    {
        return syncLogRetentionDays;
    }

    public void setSyncLogRetentionDays(int syncLogRetentionDays)
    {
        this.syncLogRetentionDays = syncLogRetentionDays;
    }

    public int getTaskRetentionDays()
    {
        return taskRetentionDays;
    }

    public void setTaskRetentionDays(int taskRetentionDays)
    {
        this.taskRetentionDays = taskRetentionDays;
    }

    public String getStatisticsStartTime()
    {
        return statisticsStartTime;
    }

    public void setStatisticsStartTime(String statisticsStartTime)
    {
        this.statisticsStartTime = statisticsStartTime;
    }
}
