package com.witos.ems.server.support;

import java.util.LinkedHashMap;
import java.util.Map;

public class EmsReportHistoryQuery
{
    private final String reportType;

    private final String periodType;

    private final Map<String, Object> params;

    public EmsReportHistoryQuery(String reportType, String periodType, Map<String, Object> params)
    {
        this.reportType = reportType;
        this.periodType = periodType;
        this.params = params == null ? new LinkedHashMap<String, Object>() : params;
    }

    public String getReportType()
    {
        return reportType;
    }

    public String getPeriodType()
    {
        return periodType;
    }

    public Map<String, Object> getParams()
    {
        return params;
    }

    public boolean isStationReport()
    {
        return "station".equals(reportType);
    }
}
