package com.witos.ems.server.service.impl;

import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.domain.entity.EmsStation;
import com.witos.ems.server.mapper.EmsReportMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.ems.server.service.EmsReportService;
import com.witos.ems.server.support.EmsBusinessParamTemplate;
import com.witos.ems.server.support.EmsReportHistoryAdapter;
import com.witos.ems.server.support.EmsReportHistoryQuery;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EmsReportServiceImpl implements EmsReportService
{
    @Resource
    private EmsReportMapper reportMapper;

    @Resource
    private EmsStationMapper stationMapper;

    @Resource
    private EmsAuthScopeService authScopeService;

    @Resource
    private EmsReportHistoryAdapter reportHistoryAdapter;

    @Resource
    private EmsViewReadSupport readSupport;

    @Resource
    private EmsPriceResolver priceResolver;

    @Resource
    private EmsBusinessParamResolver businessParamResolver;

    @Override
    public Map<String, Object> buildReport(String type, Map<String, String> query)
    {
        EmsReportHistoryQuery historyQuery = reportHistoryAdapter.adapt(type, query);
        String reportType = historyQuery.getReportType();
        boolean stationReport = historyQuery.isStationReport();
        Map<String, Object> params = historyQuery.getParams();
        boolean exportAll = "true".equalsIgnoreCase(String.valueOf(params.get("exportAll")));
        int pageNum = exportAll ? 1 : positiveInt(params.get("pageNum"), 1);
        int pageSize = exportAll ? 0 : boundedPageSize(params.get("pageSize"));
        int offset = 0;
        if (!exportAll)
        {
            long requestedOffset = (long) (pageNum - 1) * pageSize;
            offset = requestedOffset > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) requestedOffset;
            int fetchSize = offset > Integer.MAX_VALUE - pageSize ? Integer.MAX_VALUE : offset + pageSize;
            params.put("offset", 0);
            params.put("pageSize", fetchSize);
        }
        Long total = reportMapper.countReportRows(reportType, historyQuery.getPeriodType(), params, authScopeService.currentScope());
        List<Map<String, Object>> rows = reportMapper.selectReportRows(reportType, historyQuery.getPeriodType(), historyQuery.getParams(), authScopeService.currentScope());
        int currentDayAdditions = countCurrentDayAdditions(historyQuery);
        applyCurrentDayOverrides(historyQuery, rows, true, pageSize);
        sortRowsDescending(rows);
        if (!exportAll)
        {
            sliceRows(rows, offset, pageSize);
        }
        reportHistoryAdapter.adaptRows(historyQuery, rows);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        long adjustedTotal = (total == null ? 0L : total) + currentDayAdditions;
        result.put("summaryCards", buildReportSummaryCards(reportType, rows, stationReport));
        result.put("rows", rows);
        result.put("total", adjustedTotal);
        result.put("pageNum", pageNum);
        result.put("pageSize", exportAll ? adjustedTotal : pageSize);
        List<Map<String, Object>> chartRows = chronologicalRows(rows);
        result.put("chartData", buildReportChart(reportType, chartRows));
        result.put("chartXAxis", buildXAxis(chartRows));
        result.put("periodType", historyQuery.getPeriodType());
        result.put("chartSeriesNames", chartSeriesNames(reportType));
        return result;
    }

    private int positiveInt(Object value, int defaultValue)
    {
        if (value == null || String.valueOf(value).trim().isEmpty())
        {
            return defaultValue;
        }
        try
        {
            int parsed = Integer.parseInt(String.valueOf(value));
            return parsed > 0 ? parsed : defaultValue;
        }
        catch (NumberFormatException ex)
        {
            return defaultValue;
        }
    }

    private int boundedPageSize(Object value)
    {
        int parsed = positiveInt(value, 20);
        return Math.min(parsed, 200);
    }

    @Override
    public Map<String, Object> buildStationDetail(Long id)
    {
        Map<String, Object> station = stationMapper.selectStationDetail(id, authScopeService.currentScope());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("station", station == null ? new LinkedHashMap<String, Object>() : station);
        result.put("report", buildReport("station", Collections.singletonMap("stationId", String.valueOf(id))));
        return result;
    }

    private int applyCurrentDayOverrides(EmsReportHistoryQuery historyQuery, List<Map<String, Object>> rows,
                                         boolean exportAll, int pageSize)
    {
        if (rows == null || !"DAY".equals(historyQuery.getPeriodType()) || !queryIncludesToday(historyQuery.getParams()))
        {
            return 0;
        }
        int additions;
        if (historyQuery.isStationReport())
        {
            additions = applyCurrentDayStationRows(historyQuery, rows, exportAll);
        }
        else
        {
            additions = applyCurrentDayDeviceRows(historyQuery, rows, exportAll);
        }
        if (additions > 0)
        {
            sortRowsDescending(rows);
            if (!exportAll)
            {
                trimRows(rows, pageSize);
            }
        }
        return additions;
    }

    private int countCurrentDayAdditions(EmsReportHistoryQuery historyQuery)
    {
        if (!"DAY".equals(historyQuery.getPeriodType()) || !queryIncludesToday(historyQuery.getParams()))
        {
            return 0;
        }
        Map<String, Object> params = new LinkedHashMap<String, Object>(historyQuery.getParams());
        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        params.put("startTime", today);
        params.put("endTime", today);
        params.put("exportAll", "true");
        params.remove("offset");
        params.remove("pageSize");
        EmsReportHistoryQuery todayQuery = new EmsReportHistoryQuery(historyQuery.getReportType(), historyQuery.getPeriodType(), params);
        List<Map<String, Object>> persistedRows = reportMapper.selectReportRows(
                historyQuery.getReportType(), historyQuery.getPeriodType(), params, authScopeService.currentScope());
        if (historyQuery.isStationReport())
        {
            return applyCurrentDayStationRows(todayQuery, persistedRows, true);
        }
        return applyCurrentDayDeviceRows(todayQuery, persistedRows, true);
    }

    private int applyCurrentDayStationRows(EmsReportHistoryQuery historyQuery, List<Map<String, Object>> rows, boolean exportAll)
    {
        Map<String, String> query = toStringQuery(historyQuery.getParams());
        List<Map<String, Object>> stations = readSupport.stations(query);
        Map<Long, Map<String, Object>> todayMap = readSupport.stationTodayReportSummaryMap(stations);
        Date statTime = todayStart();
        int additions = 0;
        for (Map<String, Object> station : stations)
        {
            Long stationId = asLongObject(station.get("stationId"));
            Map<String, Object> metrics = todayMap.get(stationId);
            if (stationId == null || metrics == null)
            {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("stationId", stationId);
            row.put("stationName", station.get("stationName"));
            row.put("tenantId", station.get("tenantId"));
            row.put("companyId", station.get("companyId"));
            row.put("companyName", station.get("companyName"));
            row.put("generationKwh", defaultDecimal(metrics.get("generationKwh")));
            row.put("chargeKwh", defaultDecimal(metrics.get("chargeKwh")));
            row.put("dischargeKwh", defaultDecimal(metrics.get("dischargeKwh")));
            row.put("consumptionKwh", defaultDecimal(metrics.get("consumptionKwh")));
            row.put("gridImportKwh", defaultDecimal(metrics.get("gridImportKwh")));
            row.put("gridExportKwh", defaultDecimal(metrics.get("gridExportKwh")));
            row.put("statTime", statTime);
            row.put("dataQuality", metrics.get("dataQuality"));
            row.put("qualityReason", metrics.get("qualityReason"));
            enrichCurrentDayStationDerived(row);
            if (upsertCurrentDayRow(rows, "stationId", stationId, row, exportAll || isFirstPage(historyQuery.getParams())))
            {
                additions++;
            }
        }
        return additions;
    }

    private int applyCurrentDayDeviceRows(EmsReportHistoryQuery historyQuery, List<Map<String, Object>> rows, boolean exportAll)
    {
        String deviceType = deviceTypeForReport(historyQuery.getReportType());
        if (deviceType == null)
        {
            return 0;
        }
        Map<String, String> query = toStringQuery(historyQuery.getParams());
        query.put("deviceType", deviceType);
        List<Map<String, Object>> devices = readSupport.devices(query);
        Map<Long, Map<String, Object>> todayMap = readSupport.deviceTodayReportSummaryMap(devices);
        Date statTime = todayStart();
        int additions = 0;
        for (Map<String, Object> device : devices)
        {
            Long deviceId = asLongObject(device.get("deviceId"));
            Map<String, Object> metrics = todayMap.get(deviceId);
            if (deviceId == null || metrics == null)
            {
                continue;
            }
            BigDecimal charge = defaultDecimal(metrics.get("chargeKwh"));
            BigDecimal discharge = defaultDecimal(metrics.get("dischargeKwh"));
            BigDecimal peakPower = defaultDecimal(metrics.get("peakPowerKw"));
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("deviceId", deviceId);
            row.put("deviceName", device.get("deviceName"));
            row.put("deviceType", device.get("deviceType"));
            row.put("companyId", device.get("companyId"));
            row.put("companyName", device.get("companyName"));
            row.put("stationId", device.get("stationId"));
            row.put("generationKwh", defaultDecimal(metrics.get("generationKwh")));
            row.put("chargeKwh", charge);
            row.put("chargeEnergyKwh", charge);
            row.put("dischargeKwh", discharge);
            row.put("dischargeEnergyKwh", discharge);
            row.put("runtimeHours", defaultDecimal(metrics.get("runtimeHours")));
            row.put("chargeDurationHours", defaultDecimal(metrics.get("runtimeHours")));
            row.put("dischargeDurationHours", defaultDecimal(metrics.get("runtimeHours")));
            row.put("peakPowerKw", peakPower);
            row.put("activePowerKw", peakPower);
            row.put("soc", metrics.get("avgSoc"));
            row.put("avgSoc", metrics.get("avgSoc"));
            row.put("soh", metrics.get("avgSoh"));
            row.put("avgSoh", metrics.get("avgSoh"));
            row.put("alarmCount", defaultDecimal(metrics.get("alarmCount")));
            row.put("alarmSummary", defaultDecimal(metrics.get("alarmCount")) + "条");
            row.put("runningState", device.get("status"));
            row.put("batteryStatus", device.get("status"));
            row.put("faultState", device.get("status"));
            row.put("statTime", statTime);
            row.put("dataQuality", metrics.get("dataQuality"));
            row.put("qualityReason", metrics.get("qualityReason"));
            if (upsertCurrentDayRow(rows, "deviceId", deviceId, row, exportAll || isFirstPage(historyQuery.getParams())))
            {
                additions++;
            }
        }
        return additions;
    }

    private void enrichCurrentDayStationDerived(Map<String, Object> row)
    {
        Long tenantId = asLongObject(row.get("tenantId"));
        Long companyId = asLongObject(row.get("companyId"));
        Long stationId = asLongObject(row.get("stationId"));
        Date statTime = row.get("statTime") instanceof Date ? (Date) row.get("statTime") : new Date();
        BigDecimal generation = defaultDecimal(row.get("generationKwh"));
        BigDecimal charge = defaultDecimal(row.get("chargeKwh"));
        BigDecimal discharge = defaultDecimal(row.get("dischargeKwh"));
        BigDecimal gridImport = defaultDecimal(row.get("gridImportKwh"));
        BigDecimal gridExport = defaultDecimal(row.get("gridExportKwh"));
        EmsPriceResolver.RevenueBreakdown revenue = priceResolver.resolveRevenueBreakdown(tenantId, companyId, stationId,
                generation, gridExport, charge, discharge, gridImport, statTime, false);
        row.put("revenueAmount", revenue.getRevenueAmount());
        row.put("feedInRevenue", revenue.getFeedInRevenue());
        row.put("selfUseSaving", revenue.getSelfUseSaving());
        row.put("storageArbitrageRevenue", revenue.getStorageArbitrageRevenue());
        row.put("purchaseCost", revenue.getPurchaseCost());
        row.put("revenueQualityReason", revenue.getQualityReason());
        BigDecimal socialGeneration = generation.max(BigDecimal.ZERO);
        BigDecimal co2 = socialGeneration.multiply(businessParamResolver.resolveDecimal(EmsBusinessParamTemplate.CO2_FACTOR, tenantId, companyId, stationId));
        BigDecimal standardCoal = socialGeneration.multiply(businessParamResolver.resolveDecimal(EmsBusinessParamTemplate.STANDARD_COAL_FACTOR, tenantId, companyId, stationId));
        BigDecimal treeFactor = businessParamResolver.resolveDecimal(EmsBusinessParamTemplate.TREE_FACTOR, tenantId, companyId, stationId);
        row.put("equivalentHours", resolveEquivalentHours(stationId, socialGeneration));
        row.put("co2ReductionKg", co2);
        row.put("standardCoalKg", standardCoal);
        row.put("equivalentTrees", treeFactor.compareTo(BigDecimal.ZERO) > 0 ? co2.divide(treeFactor, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO);
    }

    private boolean upsertCurrentDayRow(List<Map<String, Object>> rows, String idField, Long id,
                                        Map<String, Object> currentRow, boolean allowAdd)
    {
        for (int i = 0; i < rows.size(); i++)
        {
            Map<String, Object> row = rows.get(i);
            if (id.equals(asLongObject(row.get(idField))) && isToday(row.get("statTime")))
            {
                rows.set(i, currentRow);
                return false;
            }
        }
        if (!allowAdd)
        {
            return false;
        }
        rows.add(0, currentRow);
        return true;
    }

    private void sortRowsDescending(List<Map<String, Object>> rows)
    {
        Collections.sort(rows, new Comparator<Map<String, Object>>()
        {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right)
            {
                int timeCompare = compareStatTime(right == null ? null : right.get("statTime"), left == null ? null : left.get("statTime"));
                if (timeCompare != 0)
                {
                    return timeCompare;
                }
                Long leftId = reportRowId(left);
                Long rightId = reportRowId(right);
                if (leftId == null && rightId == null)
                {
                    return 0;
                }
                if (leftId == null)
                {
                    return 1;
                }
                if (rightId == null)
                {
                    return -1;
                }
                return leftId.compareTo(rightId);
            }
        });
    }

    private Long reportRowId(Map<String, Object> row)
    {
        if (row == null)
        {
            return null;
        }
        Long deviceId = asLongObject(row.get("deviceId"));
        return deviceId == null ? asLongObject(row.get("stationId")) : deviceId;
    }

    private void trimRows(List<Map<String, Object>> rows, int pageSize)
    {
        while (pageSize > 0 && rows.size() > pageSize)
        {
            rows.remove(rows.size() - 1);
        }
    }

    private void sliceRows(List<Map<String, Object>> rows, int offset, int pageSize)
    {
        if (rows == null || rows.isEmpty())
        {
            return;
        }
        if (offset >= rows.size())
        {
            rows.clear();
            return;
        }
        int end = Math.min(rows.size(), offset + pageSize);
        List<Map<String, Object>> pageRows = new ArrayList<Map<String, Object>>(rows.subList(offset, end));
        rows.clear();
        rows.addAll(pageRows);
    }

    private boolean queryIncludesToday(Map<String, Object> params)
    {
        String today = todayDateString();
        String start = datePrefix(params == null ? null : params.get("startTime"));
        String end = datePrefix(params == null ? null : params.get("endTime"));
        return (start == null || start.compareTo(today) <= 0) && (end == null || end.compareTo(today) >= 0);
    }

    private boolean isFirstPage(Map<String, Object> params)
    {
        return params == null || positiveInt(params.get("offset"), 0) == 0;
    }

    private boolean isToday(Object value)
    {
        String date = datePrefix(value);
        return date != null && todayDateString().equals(date);
    }

    private String datePrefix(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Date)
        {
            return new SimpleDateFormat("yyyy-MM-dd").format((Date) value);
        }
        String text = String.valueOf(value);
        if (text.length() < 10)
        {
            return null;
        }
        return text.substring(0, 10);
    }

    private Date todayStart()
    {
        try
        {
            return new SimpleDateFormat("yyyy-MM-dd").parse(todayDateString());
        }
        catch (Exception ex)
        {
            return new Date();
        }
    }

    private String todayDateString()
    {
        return new SimpleDateFormat("yyyy-MM-dd").format(new Date());
    }

    private Map<String, String> toStringQuery(Map<String, Object> params)
    {
        Map<String, String> query = new LinkedHashMap<String, String>();
        if (params == null)
        {
            return query;
        }
        for (Map.Entry<String, Object> entry : params.entrySet())
        {
            if (entry.getValue() != null)
            {
                query.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return query;
    }

    private String deviceTypeForReport(String reportType)
    {
        if ("inverter".equals(reportType))
        {
            return "INVERTER";
        }
        if ("pcs".equals(reportType))
        {
            return "PCS";
        }
        if ("storage".equals(reportType))
        {
            return "ESS";
        }
        if ("meter".equals(reportType))
        {
            return "METER";
        }
        if ("controller".equals(reportType))
        {
            return "CONTROLLER";
        }
        return null;
    }

    @Override
    public Map<String, Object> reportLifecycle(String type, Map<String, String> query)
    {
        EmsReportHistoryQuery historyQuery = reportHistoryAdapter.adapt(type, query);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> periods = new ArrayList<Map<String, Object>>();
        for (String periodType : Arrays.asList("HOUR", "DAY", "MONTH", "YEAR"))
        {
            Map<String, Object> row = reportMapper.selectLifecycleSummary(historyQuery.getReportType(), periodType, historyQuery.getParams(), authScopeService.currentScope());
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("periodType", periodType);
            item.put("recordCount", asLong(row == null ? null : row.get("recordCount")));
            item.put("minStatTime", formatDate(row == null ? null : row.get("minStatTime")));
            item.put("maxStatTime", formatDate(row == null ? null : row.get("maxStatTime")));
            item.put("badQualityCount", asLong(row == null ? null : row.get("badQualityCount")));
            periods.add(item);
        }
        result.put("reportType", historyQuery.getReportType());
        result.put("periods", periods);
        return result;
    }

    private Map<String, Object> buildReportChart(String reportType, List<Map<String, Object>> rows)
    {
        List<Object> expected = new ArrayList<Object>();
        List<Object> actual = new ArrayList<Object>();
        for (Map<String, Object> row : rows)
        {
            expected.add(chartDecimal(primaryChartValue(reportType, row), false));
            actual.add(chartDecimal(secondChartValue(reportType, row), "storage".equals(reportType)));
        }
        Map<String, Object> chart = new LinkedHashMap<String, Object>();
        chart.put("expectedData", expected);
        chart.put("actualData", actual);
        return chart;
    }

    private List<String> buildXAxis(List<Map<String, Object>> rows)
    {
        List<String> xAxis = new ArrayList<String>();
        for (Map<String, Object> row : rows)
        {
            xAxis.add(String.valueOf(row.get("statTime")));
        }
        return xAxis;
    }

    private List<Map<String, Object>> chronologicalRows(List<Map<String, Object>> rows)
    {
        List<Map<String, Object>> chartRows = new ArrayList<Map<String, Object>>(rows);
        Collections.sort(chartRows, new Comparator<Map<String, Object>>()
        {
            @Override
            public int compare(Map<String, Object> left, Map<String, Object> right)
            {
                return compareStatTime(left == null ? null : left.get("statTime"), right == null ? null : right.get("statTime"));
            }
        });
        return chartRows;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private int compareStatTime(Object left, Object right)
    {
        if (left == null && right == null)
        {
            return 0;
        }
        if (left == null)
        {
            return 1;
        }
        if (right == null)
        {
            return -1;
        }
        if (left instanceof Comparable && left.getClass().isInstance(right))
        {
            return ((Comparable) left).compareTo(right);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private List<Map<String, Object>> buildReportSummaryCards(String reportType, List<Map<String, Object>> rows, boolean stationReport)
    {
        if ("station".equals(reportType))
        {
            return buildStationSummaryCards(rows);
        }
        if ("inverter".equals(reportType))
        {
            return buildInverterSummaryCards(rows);
        }
        if ("pcs".equals(reportType))
        {
            return buildPcsSummaryCards(rows);
        }
        if ("storage".equals(reportType))
        {
            return buildStorageSummaryCards(rows);
        }
        return buildDefaultSummaryCards(rows, stationReport);
    }

    private List<Map<String, Object>> buildStationSummaryCards(List<Map<String, Object>> rows)
    {
        BigDecimal generation = BigDecimal.ZERO;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal co2 = BigDecimal.ZERO;
        BigDecimal export = BigDecimal.ZERO;
        for (Map<String, Object> row : rows)
        {
            generation = generation.add(defaultDecimal(row.get("generationKwh")));
            revenue = revenue.add(defaultDecimal(row.get("revenueAmount")));
            co2 = co2.add(defaultDecimal(row.get("co2ReductionKg")));
            export = export.add(defaultDecimal(row.get("gridExportKwh")));
        }
        List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
        cards.add(card("总发电", generation, "kWh", "当前周期"));
        cards.add(card("总收益", revenue, "元", "当前周期"));
        cards.add(card("减碳", co2, "kg", "当前周期"));
        cards.add(card("总上网电量", export, "kWh", "当前周期"));
        cards.add(card("质量异常", countBadQuality(rows), "条", "非GOOD记录"));
        return cards;
    }

    private List<Map<String, Object>> buildInverterSummaryCards(List<Map<String, Object>> rows)
    {
        BigDecimal generation = BigDecimal.ZERO;
        BigDecimal runtime = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        for (Map<String, Object> row : rows)
        {
            generation = generation.add(defaultDecimal(row.get("generationKwh")));
            runtime = runtime.add(defaultDecimal(row.get("runtimeHours")));
            peak = peak.max(defaultDecimal(row.get("peakPowerKw")));
        }
        List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
        cards.add(card("总发电", generation, "kWh", "当前周期"));
        cards.add(card("总运行时长", runtime, "h", "当前周期"));
        cards.add(card("峰值功率", peak, "kW", "当前结果最大值"));
        cards.add(card("质量异常", countBadQuality(rows), "条", "非GOOD记录"));
        return cards;
    }

    private List<Map<String, Object>> buildPcsSummaryCards(List<Map<String, Object>> rows)
    {
        BigDecimal charge = BigDecimal.ZERO;
        BigDecimal discharge = BigDecimal.ZERO;
        BigDecimal active = BigDecimal.ZERO;
        for (Map<String, Object> row : rows)
        {
            charge = charge.add(defaultDecimal(row.get("chargeEnergyKwh")));
            discharge = discharge.add(defaultDecimal(row.get("dischargeEnergyKwh")));
            active = active.add(defaultDecimal(row.get("activePowerKw")));
        }
        List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
        cards.add(card("总充电量", charge, "kWh", "当前周期"));
        cards.add(card("总放电量", discharge, "kWh", "当前周期"));
        cards.add(card("平均有功功率", average(active, rows.size()), "kW", "当前结果均值"));
        cards.add(card("故障记录", countByState(rows, "faultState", "FAULT"), "条", "当前结果"));
        return cards;
    }

    private List<Map<String, Object>> buildStorageSummaryCards(List<Map<String, Object>> rows)
    {
        BigDecimal charge = BigDecimal.ZERO;
        BigDecimal discharge = BigDecimal.ZERO;
        BigDecimal soc = BigDecimal.ZERO;
        BigDecimal soh = BigDecimal.ZERO;
        int socCount = 0;
        int sohCount = 0;
        for (Map<String, Object> row : rows)
        {
            charge = charge.add(defaultDecimal(row.get("chargeEnergyKwh")));
            discharge = discharge.add(defaultDecimal(row.get("dischargeEnergyKwh")));
            BigDecimal socValue = nullableDecimal(row.get("soc"));
            if (socValue != null)
            {
                soc = soc.add(socValue);
                socCount++;
            }
            BigDecimal sohValue = nullableDecimal(row.get("soh"));
            if (sohValue != null)
            {
                soh = soh.add(sohValue);
                sohCount++;
            }
        }
        List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
        cards.add(card("平均SOC", averageNullable(soc, socCount), "%", "当前结果均值"));
        cards.add(card("平均SOH", averageNullable(soh, sohCount), "%", "当前结果均值"));
        cards.add(card("总充电量", charge, "kWh", "当前周期"));
        cards.add(card("总放电量", discharge, "kWh", "当前周期"));
        return cards;
    }

    private List<Map<String, Object>> buildDefaultSummaryCards(List<Map<String, Object>> rows, boolean stationReport)
    {
        BigDecimal generation = BigDecimal.ZERO;
        BigDecimal second = BigDecimal.ZERO;
        for (Map<String, Object> row : rows)
        {
            generation = generation.add(defaultDecimal(row.get("generationKwh")));
            second = second.add(defaultDecimal(summarySecondValue(row, stationReport)));
        }
        List<Map<String, Object>> cards = new ArrayList<Map<String, Object>>();
        cards.add(card("总发电", generation, "kWh", "当前周期"));
        cards.add(card(stationReport ? "总收益" : "总充电量", second, stationReport ? "元" : "kWh", "当前周期"));
        cards.add(card("记录数", rows.size(), "条", "统计结果"));
        cards.add(card("质量异常", countBadQuality(rows), "条", "非GOOD记录"));
        return cards;
    }

    private int countBadQuality(List<Map<String, Object>> rows)
    {
        int count = 0;
        for (Map<String, Object> row : rows)
        {
            Object dataQuality = row.get("dataQuality");
            if (dataQuality == null || !"GOOD".equalsIgnoreCase(String.valueOf(dataQuality)))
            {
                count++;
            }
        }
        return count;
    }

    private Map<String, Object> card(String title, Object value, String unit, String subtitle)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("title", title);
        row.put("value", value);
        row.put("unit", unit);
        row.put("subtitle", subtitle);
        return row;
    }

    private BigDecimal defaultDecimal(Object value)
    {
        if (value == null)
        {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal)
        {
            return (BigDecimal) value;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private BigDecimal nullableDecimal(Object value)
    {
        if (value == null || String.valueOf(value).trim().isEmpty() || "null".equalsIgnoreCase(String.valueOf(value)))
        {
            return null;
        }
        if (value instanceof BigDecimal)
        {
            return (BigDecimal) value;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private Map<String, Object> queryMap(Map<String, String> query)
    {
        return query == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(query);
    }

    private String formatDate(Object value)
    {
        if (!(value instanceof Date))
        {
            return "";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((Date) value);
    }

    private long asLong(Object value)
    {
        if (value == null)
        {
            return 0L;
        }
        if (value instanceof Number)
        {
            return ((Number) value).longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Long asLongObject(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Number)
        {
            return ((Number) value).longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Object primaryChartValue(String reportType, Map<String, Object> row)
    {
        if ("station".equals(reportType) || "inverter".equals(reportType))
        {
            return row.get("generationKwh");
        }
        if ("storage".equals(reportType))
        {
            return row.get("soc");
        }
        return row.get("chargeEnergyKwh");
    }

    private Object secondChartValue(String reportType, Map<String, Object> row)
    {
        if ("station".equals(reportType))
        {
            return row.get("revenueAmount");
        }
        if ("storage".equals(reportType))
        {
            return row.get("soh");
        }
        if ("inverter".equals(reportType))
        {
            return row.get("activePowerKw");
        }
        return row.get("dischargeEnergyKwh");
    }

    private Object summarySecondValue(Map<String, Object> row, boolean stationReport)
    {
        if (stationReport)
        {
            return row.get("revenueAmount");
        }
        return row.containsKey("chargeEnergyKwh") ? row.get("chargeEnergyKwh") : row.get("chargeKwh");
    }

    private BigDecimal resolveEquivalentHours(Long stationId, BigDecimal generationKwh)
    {
        if (stationId == null || stationId <= 0 || generationKwh == null || generationKwh.compareTo(BigDecimal.ZERO) <= 0)
        {
            return BigDecimal.ZERO;
        }
        EmsStation station = stationMapper.selectById(stationId);
        BigDecimal capacityKw = station == null ? null : station.getCapacityKw();
        if (capacityKw == null || capacityKw.compareTo(BigDecimal.ZERO) <= 0)
        {
            return BigDecimal.ZERO;
        }
        return generationKwh.divide(capacityKw, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal average(BigDecimal sum, int size)
    {
        if (size <= 0)
        {
            return BigDecimal.ZERO;
        }
        return sum.divide(BigDecimal.valueOf(size), 2, BigDecimal.ROUND_HALF_UP);
    }

    private BigDecimal averageNullable(BigDecimal sum, int size)
    {
        if (size <= 0)
        {
            return null;
        }
        return average(sum, size);
    }

    private Object chartDecimal(Object value, boolean keepMissing)
    {
        if (keepMissing && nullableDecimal(value) == null)
        {
            return null;
        }
        return defaultDecimal(value);
    }

    private long countByState(List<Map<String, Object>> rows, String field, String state)
    {
        long count = 0L;
        for (Map<String, Object> row : rows)
        {
            Object value = row.get(field);
            if (value != null && state.equalsIgnoreCase(String.valueOf(value)))
            {
                count++;
            }
        }
        return count;
    }

    private List<String> chartSeriesNames(String reportType)
    {
        if ("station".equals(reportType))
        {
            return Arrays.asList("发电量", "收益");
        }
        if ("inverter".equals(reportType))
        {
            return Arrays.asList("发电量", "平均功率");
        }
        if ("pcs".equals(reportType))
        {
            return Arrays.asList("充电电量", "放电电量");
        }
        if ("storage".equals(reportType))
        {
            return Arrays.asList("SOC", "SOH");
        }
        return Arrays.asList("数值1", "数值2");
    }
}
