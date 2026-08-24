package com.witos.ems.server.support;

import com.witos.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EmsReportHistoryAdapter
{
    public EmsReportHistoryQuery adapt(String reportType, Map<String, String> query)
    {
        Map<String, Object> params = query == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(query);
        String periodType = normalizePeriodType(String.valueOf(params.get("periodType")));
        params.put("periodType", periodType);
        return new EmsReportHistoryQuery(normalizeReportType(reportType), periodType, params);
    }

    public void adaptRows(EmsReportHistoryQuery query, List<Map<String, Object>> rows)
    {
        if (rows == null)
        {
            return;
        }
        for (Map<String, Object> row : rows)
        {
            fillQuality(row);
            if (query.isStationReport())
            {
                adaptStationRow(row);
            }
            else if ("inverter".equals(query.getReportType()))
            {
                adaptInverterRow(row);
            }
            else if ("pcs".equals(query.getReportType()))
            {
                adaptPcsRow(row);
            }
            else if ("storage".equals(query.getReportType()))
            {
                adaptStorageRow(row);
            }
        }
    }

    private void adaptStationRow(Map<String, Object> row)
    {
        row.put("generationKwh", decimal(row.get("generationKwh")));
        row.put("chargeKwh", decimal(row.get("chargeKwh")));
        row.put("dischargeKwh", decimal(row.get("dischargeKwh")));
        row.put("revenueAmount", decimal(row.get("revenueAmount")));
        row.put("feedInRevenue", nullableDecimal(row.get("feedInRevenue")));
        row.put("selfUseSaving", nullableDecimal(row.get("selfUseSaving")));
        row.put("storageArbitrageRevenue", nullableDecimal(row.get("storageArbitrageRevenue")));
        row.put("purchaseCost", nullableDecimal(row.get("purchaseCost")));
        if (emptyValue(row.get("revenueQualityReason")) && (row.get("feedInRevenue") == null || row.get("selfUseSaving") == null))
        {
            row.put("revenueQualityReason", "历史数据未拆分收益");
        }
        row.put("equivalentHours", decimal(row.get("equivalentHours")));
        row.put("co2ReductionKg", decimal(row.get("co2ReductionKg")));
        row.put("standardCoalKg", decimal(row.get("standardCoalKg")));
        row.put("equivalentTrees", decimal(row.get("equivalentTrees")));
    }

    private void adaptInverterRow(Map<String, Object> row)
    {
        row.put("generationKwh", decimal(row.get("generationKwh")));
        row.put("activePowerKw", decimal(row.get("activePowerKw")));
        row.put("runtimeHours", decimal(row.get("runtimeHours")));
        row.put("peakPowerKw", decimal(row.get("peakPowerKw")));
        BigDecimal activePower = decimal(row.get("activePowerKw"));
        BigDecimal peakPower = decimal(row.get("peakPowerKw"));
        row.put("efficiencyRate", peakPower.compareTo(BigDecimal.ZERO) > 0
                ? activePower.multiply(new BigDecimal("100")).divide(peakPower, 2, BigDecimal.ROUND_HALF_UP)
                : BigDecimal.ZERO);
    }

    private void adaptPcsRow(Map<String, Object> row)
    {
        row.put("chargeEnergyKwh", decimal(row.get("chargeEnergyKwh")));
        row.put("dischargeEnergyKwh", decimal(row.get("dischargeEnergyKwh")));
        row.put("activePowerKw", decimal(row.get("activePowerKw")));
        row.put("runningState", emptyValue(row.get("runningState")) ? "UNKNOWN" : row.get("runningState"));
        row.put("faultState", asInteger(row.get("alarmCount")) > 0 ? "WARNING" : "NORMAL");
    }

    private void adaptStorageRow(Map<String, Object> row)
    {
        row.put("soc", decimal(row.get("soc")));
        row.put("soh", nullableDecimal(row.get("soh")));
        row.put("chargeEnergyKwh", decimal(row.get("chargeEnergyKwh")));
        row.put("dischargeEnergyKwh", decimal(row.get("dischargeEnergyKwh")));
        row.put("chargeDurationHours", decimal(row.get("chargeDurationHours")));
        row.put("dischargeDurationHours", decimal(row.get("dischargeDurationHours")));
        row.put("batteryStatus", emptyValue(row.get("batteryStatus")) ? "UNKNOWN" : row.get("batteryStatus"));
        row.put("alarmSummary", asInteger(row.get("alarmCount")) > 0 ? "存在告警" : "无告警");
    }

    private void fillQuality(Map<String, Object> row)
    {
        if (emptyValue(row.get("dataQuality")))
        {
            row.put("dataQuality", "MISSING");
        }
        if (emptyValue(row.get("qualityReason")))
        {
            row.put("qualityReason", "未提供质量说明");
        }
    }

    private String normalizeReportType(String reportType)
    {
        return reportType == null ? "station" : reportType.toLowerCase();
    }

    private String normalizePeriodType(String periodType)
    {
        if (StringUtils.isEmpty(periodType) || "null".equalsIgnoreCase(periodType))
        {
            return "DAY";
        }
        String value = periodType.toUpperCase();
        if ("HOUR".equals(value) || "DAY".equals(value) || "MONTH".equals(value) || "YEAR".equals(value))
        {
            return value;
        }
        return "DAY";
    }

    private BigDecimal decimal(Object value)
    {
        if (value == null)
        {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal)
        {
            return ((BigDecimal) value).setScale(2, BigDecimal.ROUND_HALF_UP);
        }
        return new BigDecimal(String.valueOf(value)).setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    private BigDecimal nullableDecimal(Object value)
    {
        if (value == null)
        {
            return null;
        }
        return decimal(value);
    }

    private int asInteger(Object value)
    {
        if (value == null)
        {
            return 0;
        }
        if (value instanceof Number)
        {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private boolean emptyValue(Object value)
    {
        return value == null || StringUtils.isEmpty(String.valueOf(value)) || "null".equalsIgnoreCase(String.valueOf(value));
    }
}
