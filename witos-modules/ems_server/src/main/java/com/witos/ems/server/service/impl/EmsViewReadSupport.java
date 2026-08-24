package com.witos.ems.server.service.impl;

import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.mapper.EmsAlarmEventMapper;
import com.witos.ems.server.mapper.EmsDeviceMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.ems.server.mapper.EmsViewMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class EmsViewReadSupport
{
    @Resource
    private EmsStationMapper stationMapper;

    @Resource
    private EmsDeviceMapper deviceMapper;

    @Resource
    private EmsViewMapper viewMapper;

    @Resource
    private EmsAuthScopeService authScopeService;

    @Resource
    private EmsAlarmEventMapper alarmEventMapper;

    public List<Map<String, Object>> stations(Map<String, String> query)
    {
        return stationMapper.selectStationList(queryMap(query), authScopeService.currentScope());
    }

    public Map<String, Object> station(Long stationId)
    {
        Map<String, Object> detail = stationMapper.selectStationDetail(stationId, authScopeService.currentScope());
        return detail == null ? new LinkedHashMap<String, Object>() : detail;
    }

    public List<Map<String, Object>> devices(Map<String, String> query)
    {
        return deviceMapper.selectDeviceList(queryMap(query), authScopeService.currentScope());
    }

    public Map<String, Object> device(Long deviceId)
    {
        Map<String, Object> detail = deviceMapper.selectDeviceDetail(deviceId, authScopeService.currentScope());
        return detail == null ? new LinkedHashMap<String, Object>() : detail;
    }

    public List<Map<String, Object>> currentAlarms(Map<String, String> query)
    {
        return alarmEventMapper.selectCurrentAlarmList(queryMap(query), authScopeService.currentScope());
    }

    public Map<Long, BigDecimal> stationGenerationMap(List<Map<String, Object>> stations)
    {
        List<Long> stationIds = EmsViewValueSupport.idList(stations, "stationId");
        if (stationIds.isEmpty())
        {
            return Collections.emptyMap();
        }
        return EmsViewValueSupport.decimalMap(viewMapper.selectStationHomeMetricSummary(stationIds), "stationId", "todayGenerationKwh");
    }

    public Map<Long, BigDecimal> stationRevenueMap(List<Map<String, Object>> stations)
    {
        List<Long> stationIds = EmsViewValueSupport.idList(stations, "stationId");
        if (stationIds.isEmpty())
        {
            return Collections.emptyMap();
        }
        return EmsViewValueSupport.decimalMap(viewMapper.selectStationRevenueSummary(stationIds), "stationId", "revenueAmount");
    }

    public Map<Long, Map<String, Object>> stationRevenueQualityMap(List<Map<String, Object>> stations)
    {
        List<Long> stationIds = EmsViewValueSupport.idList(stations, "stationId");
        if (stationIds.isEmpty())
        {
            return Collections.emptyMap();
        }
        Map<Long, Map<String, Object>> result = new LinkedHashMap<Long, Map<String, Object>>();
        for (Map<String, Object> row : viewMapper.selectStationRevenueQuality(stationIds))
        {
            Long stationId = EmsViewValueSupport.asLong(row.get("stationId"));
            if (stationId != null)
            {
                result.put(stationId, row);
            }
        }
        return result;
    }

    public Map<Long, Map<String, Object>> stationHomeMetricMap(List<Map<String, Object>> stations)
    {
        List<Long> stationIds = EmsViewValueSupport.idList(stations, "stationId");
        if (stationIds.isEmpty())
        {
            return Collections.emptyMap();
        }
        Map<Long, Map<String, Object>> result = new LinkedHashMap<Long, Map<String, Object>>();
        for (Map<String, Object> row : viewMapper.selectStationHomeMetricSummary(stationIds))
        {
            Long stationId = EmsViewValueSupport.asLong(row.get("stationId"));
            if (stationId != null)
            {
                result.put(stationId, row);
            }
        }
        return result;
    }

    public Map<Long, Map<String, Object>> stationTodayReportSummaryMap(List<Map<String, Object>> stations)
    {
        List<Long> stationIds = EmsViewValueSupport.idList(stations, "stationId");
        if (stationIds.isEmpty())
        {
            return Collections.emptyMap();
        }
        Map<Long, Map<String, Object>> result = new LinkedHashMap<Long, Map<String, Object>>();
        for (Map<String, Object> row : viewMapper.selectStationTodayReportSummary(stationIds))
        {
            Long stationId = EmsViewValueSupport.asLong(row.get("stationId"));
            if (stationId != null)
            {
                result.put(stationId, row);
            }
        }
        return result;
    }

    public Map<Long, Map<String, Object>> deviceTodayReportSummaryMap(List<Map<String, Object>> devices)
    {
        List<Long> deviceIds = EmsViewValueSupport.idList(devices, "deviceId");
        if (deviceIds.isEmpty())
        {
            return Collections.emptyMap();
        }
        Map<Long, Map<String, Object>> result = new LinkedHashMap<Long, Map<String, Object>>();
        for (Map<String, Object> row : viewMapper.selectDeviceTodayReportSummary(deviceIds))
        {
            Long deviceId = EmsViewValueSupport.asLong(row.get("deviceId"));
            if (deviceId != null)
            {
                result.put(deviceId, row);
            }
        }
        return result;
    }

    public BigDecimal deviceTodayGeneration(Long deviceId)
    {
        if (deviceId == null)
        {
            return BigDecimal.ZERO;
        }
        Map<String, Object> device = new LinkedHashMap<String, Object>();
        device.put("deviceId", deviceId);
        Map<Long, Map<String, Object>> summaryMap = deviceTodayReportSummaryMap(Collections.singletonList(device));
        Map<String, Object> summary = summaryMap.get(deviceId);
        return summary == null ? BigDecimal.ZERO : EmsViewValueSupport.asDecimal(summary.get("generationKwh"));
    }

    public Map<String, Object> stationDailyTrendChart(List<Map<String, Object>> stations)
    {
        List<Long> stationIds = EmsViewValueSupport.idList(stations, "stationId");
        if (stationIds.isEmpty())
        {
            return emptyChart();
        }
        List<String> xAxisData = new ArrayList<String>();
        List<BigDecimal> generationData = new ArrayList<BigDecimal>();
        List<BigDecimal> revenueData = new ArrayList<BigDecimal>();
        for (Map<String, Object> row : viewMapper.selectStationDailyTrend(stationIds))
        {
            xAxisData.add(String.valueOf(row.get("statDate")));
            generationData.add(EmsViewValueSupport.asDecimal(row.get("generationKwh")));
            revenueData.add(EmsViewValueSupport.asDecimal(row.get("revenueAmount")));
        }
        Map<String, Object> chart = new LinkedHashMap<String, Object>();
        chart.put("xAxisData", xAxisData);
        chart.put("actualData", generationData);
        chart.put("expectedData", revenueData);
        return chart;
    }

    public Map<String, Object> stationRealtimeTrendChart(List<Map<String, Object>> stations)
    {
        List<Long> stationIds = EmsViewValueSupport.idList(stations, "stationId");
        if (stationIds.isEmpty())
        {
            return emptyChart();
        }
        List<String> xAxisData = new ArrayList<String>();
        List<BigDecimal> generationData = new ArrayList<BigDecimal>();
        List<BigDecimal> activePowerData = new ArrayList<BigDecimal>();
        for (Map<String, Object> row : viewMapper.selectStationRealtimeTrend(stationIds))
        {
            xAxisData.add(String.valueOf(row.get("bucketLabel")));
            generationData.add(EmsViewValueSupport.asDecimal(row.get("generationKwh")));
            activePowerData.add(EmsViewValueSupport.asDecimal(row.get("activePowerKw")));
        }
        Map<String, Object> chart = new LinkedHashMap<String, Object>();
        chart.put("xAxisData", xAxisData);
        chart.put("expectedData", generationData);
        chart.put("actualData", activePowerData);
        chart.put("seriesNames", Arrays.asList("发电量(kWh/5min)", "实时功率(kW)"));
        return chart;
    }

    public Map<Long, String> latestSampleTimeMap(List<Map<String, Object>> stations)
    {
        List<Long> stationIds = EmsViewValueSupport.idList(stations, "stationId");
        if (stationIds.isEmpty())
        {
            return Collections.emptyMap();
        }
        Map<Long, String> result = new LinkedHashMap<Long, String>();
        List<Map<String, Object>> rows = viewMapper.selectLatestSampleTimes(stationIds);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (Map<String, Object> row : rows)
        {
            Long stationId = EmsViewValueSupport.asLong(row.get("stationId"));
            Object sampleTime = row.get("sampleTime");
            if (stationId == null || !(sampleTime instanceof Date))
            {
                continue;
            }
            result.put(stationId, format.format((Date) sampleTime));
        }
        return result;
    }

    public Map<Long, Map<String, Object>> stationSyncStatusMap(List<Map<String, Object>> stations)
    {
        List<Long> stationIds = EmsViewValueSupport.idList(stations, "stationId");
        if (stationIds.isEmpty())
        {
            return Collections.emptyMap();
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Map<Long, Map<String, Object>> result = new LinkedHashMap<Long, Map<String, Object>>();
        for (Map<String, Object> row : viewMapper.selectStationSyncStatus(stationIds))
        {
            Long stationId = EmsViewValueSupport.asLong(row.get("stationId"));
            if (stationId == null)
            {
                continue;
            }
            Map<String, Object> status = new LinkedHashMap<String, Object>();
            status.put("syncStatus", StringUtils.defaultString(String.valueOf(row.get("syncStatus")), "MISSING"));
            status.put("syncMessage", row.get("syncMessage"));
            status.put("latestSampleTime", formatDate(row.get("latestSampleTime"), format));
            status.put("lastAttemptTime", formatDate(row.get("lastAttemptTime"), format));
            result.put(stationId, status);
        }
        return result;
    }

    private String formatDate(Object value, SimpleDateFormat format)
    {
        return value instanceof Date ? format.format((Date) value) : "";
    }

    public Map<Long, Integer> deviceAlarmCountMap(List<Map<String, Object>> devices)
    {
        List<Long> deviceIds = EmsViewValueSupport.idList(devices, "deviceId");
        if (deviceIds.isEmpty())
        {
            return Collections.emptyMap();
        }
        return EmsViewValueSupport.integerMap(viewMapper.selectActiveAlarmCountByDeviceIds(deviceIds), "deviceId", "alarmCount");
    }

    public void enrichDevicesWithRealtimeQuality(List<Map<String, Object>> devices)
    {
        List<Long> deviceIds = EmsViewValueSupport.idList(devices, "deviceId");
        if (deviceIds.isEmpty())
        {
            return;
        }
        Map<Long, Map<String, Object>> qualityMap = new LinkedHashMap<Long, Map<String, Object>>();
        for (Map<String, Object> row : viewMapper.selectDeviceSnapshotQualityByDeviceIds(deviceIds))
        {
            Long deviceId = EmsViewValueSupport.asLong(row.get("deviceId"));
            if (deviceId != null)
            {
                qualityMap.put(deviceId, row);
            }
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (Map<String, Object> device : devices)
        {
            Long deviceId = EmsViewValueSupport.asLong(device.get("deviceId"));
            Map<String, Object> quality = qualityMap.get(deviceId);
            if (quality == null)
            {
                device.put("realtimeQuality", "MISSING");
                device.put("realtimeQualityReason", "未找到OpenEMS实时采样快照");
                device.put("realtimeSampleTime", "");
                continue;
            }
            device.put("realtimeQuality", quality.get("realtimeQuality"));
            device.put("realtimeQualityReason", quality.get("realtimeQualityReason"));
            Object sampleTime = quality.get("sampleTime");
            device.put("realtimeSampleTime", sampleTime instanceof Date ? format.format((Date) sampleTime) : "");
        }
    }

    public List<Map<String, Object>> deviceHierarchyTree(Long stationId, List<Map<String, Object>> devices)
    {
        List<Map<String, Object>> stationDevices = stationDevices(stationId, devices);
        if (stationDevices.isEmpty())
        {
            return Collections.emptyList();
        }
        List<Map<String, Object>> hierarchyRows = viewMapper.selectDeviceHierarchyRows(stationId);
        if (hierarchyRows.isEmpty())
        {
            return stationDevices;
        }
        Map<Long, Map<String, Object>> deviceMap = new LinkedHashMap<Long, Map<String, Object>>();
        for (Map<String, Object> device : stationDevices)
        {
            device.put("children", new ArrayList<Map<String, Object>>());
            Long deviceId = EmsViewValueSupport.asLong(device.get("deviceId"));
            if (deviceId != null)
            {
                deviceMap.put(deviceId, device);
            }
        }
        List<Map<String, Object>> roots = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : hierarchyRows)
        {
            Long deviceId = EmsViewValueSupport.asLong(row.get("deviceId"));
            Map<String, Object> device = deviceMap.get(deviceId);
            if (device == null)
            {
                continue;
            }
            device.put("levelNo", row.get("levelNo"));
            device.put("path", row.get("path"));
            Long parentDeviceId = EmsViewValueSupport.asLong(row.get("parentDeviceId"));
            Map<String, Object> parent = deviceMap.get(parentDeviceId);
            if (parent == null)
            {
                roots.add(device);
            }
            else
            {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) parent.get("children");
                children.add(device);
            }
        }
        return roots.isEmpty() ? stationDevices : roots;
    }

    public Map<String, Object> deviceMetricTrendChart(Long deviceId, List<String> actualMetricKeys, List<String> expectedMetricKeys)
    {
        if (deviceId == null)
        {
            return emptyChart();
        }
        List<String> metricKeys = new ArrayList<String>();
        if (actualMetricKeys != null)
        {
            metricKeys.addAll(actualMetricKeys);
        }
        if (expectedMetricKeys != null)
        {
            metricKeys.addAll(expectedMetricKeys);
        }
        if (metricKeys.isEmpty())
        {
            return emptyChart();
        }
        Set<String> actualSet = new HashSet<String>(actualMetricKeys == null ? Collections.<String>emptyList() : actualMetricKeys);
        Set<String> expectedSet = new HashSet<String>(expectedMetricKeys == null ? Collections.<String>emptyList() : expectedMetricKeys);
        boolean hasActualSeries = !actualSet.isEmpty();
        boolean hasExpectedSeries = !expectedSet.isEmpty();
        boolean expectedGeneration = containsGenerationMetric(expectedSet);
        int actualSampleCount = 0;
        int expectedSampleCount = 0;
        int expectedFallbackSampleCount = 0;
        Map<String, BigDecimal> actualByTime = new LinkedHashMap<String, BigDecimal>();
        Map<String, BigDecimal> expectedByTime = new LinkedHashMap<String, BigDecimal>();
        Map<String, BigDecimal> expectedPowerFallbackByTime = new LinkedHashMap<String, BigDecimal>();
        for (Map<String, Object> row : viewMapper.selectDeviceMetricTrend(deviceId, metricKeys))
        {
            String bucketLabel = String.valueOf(row.get("bucketLabel"));
            String metricKey = String.valueOf(row.get("metricKey"));
            BigDecimal metricValue = EmsViewValueSupport.asDecimal(row.get("metricValue"));
            if (!actualByTime.containsKey(bucketLabel))
            {
                actualByTime.put(bucketLabel, BigDecimal.ZERO);
                if (hasExpectedSeries)
                {
                    expectedByTime.put(bucketLabel, BigDecimal.ZERO);
                    expectedPowerFallbackByTime.put(bucketLabel, BigDecimal.ZERO);
                }
            }
            if (hasActualSeries && actualSet.contains(metricKey))
            {
                actualByTime.put(bucketLabel, actualByTime.get(bucketLabel).add(metricValue));
                actualSampleCount++;
            }
            if (hasExpectedSeries && expectedSet.contains(metricKey))
            {
                expectedByTime.put(bucketLabel, expectedByTime.get(bucketLabel).add(metricValue));
                expectedSampleCount++;
            }
            if (hasExpectedSeries && expectedGeneration && isPowerMetric(metricKey))
            {
                expectedPowerFallbackByTime.put(bucketLabel, expectedPowerFallbackByTime.get(bucketLabel).add(metricValue.abs().divide(BigDecimal.valueOf(12), 8, BigDecimal.ROUND_HALF_UP)));
                expectedFallbackSampleCount++;
            }
        }
        if (hasExpectedSeries && expectedGeneration && expectedFallbackSampleCount > 0)
        {
            for (String bucketLabel : expectedByTime.keySet())
            {
                if (expectedByTime.get(bucketLabel).compareTo(BigDecimal.ZERO) == 0)
                {
                    expectedByTime.put(bucketLabel, expectedPowerFallbackByTime.get(bucketLabel));
                }
            }
            expectedSampleCount += expectedFallbackSampleCount;
        }
        Map<String, Object> chart = new LinkedHashMap<String, Object>();
        chart.put("xAxisData", new ArrayList<String>(actualByTime.keySet()));
        chart.put("actualData", actualSampleCount > 0 ? new ArrayList<BigDecimal>(actualByTime.values()) : Collections.emptyList());
        chart.put("expectedData", expectedSampleCount > 0 ? new ArrayList<BigDecimal>(expectedByTime.values()) : Collections.emptyList());
        chart.put("seriesNames", trendSeriesNames(actualSampleCount > 0 ? actualSet : Collections.<String>emptySet(),
                expectedSampleCount > 0 ? expectedSet : Collections.<String>emptySet()));
        return chart;
    }

    public List<Map<String, Object>> deviceMetricHistory(List<Long> deviceIds, String metricKey,
                                                         Date startTime, Date endTime)
    {
        if (deviceIds == null || deviceIds.isEmpty())
        {
            return Collections.emptyList();
        }
        return viewMapper.selectDeviceMetricHistory(deviceIds, metricKey, startTime, endTime);
    }

    public Map<String, Object> deviceStoragePowerTrendChart(Long deviceId)
    {
        if (deviceId == null)
        {
            return emptyChart();
        }
        List<String> xAxisData = new ArrayList<String>();
        List<BigDecimal> dischargeData = new ArrayList<BigDecimal>();
        List<BigDecimal> chargeData = new ArrayList<BigDecimal>();
        for (Map<String, Object> row : viewMapper.selectDeviceStoragePowerTrend(deviceId))
        {
            xAxisData.add(String.valueOf(row.get("bucketLabel")));
            dischargeData.add(EmsViewValueSupport.asDecimal(row.get("dischargePowerKw")));
            chargeData.add(EmsViewValueSupport.asDecimal(row.get("chargePowerKw")));
        }
        Map<String, Object> chart = new LinkedHashMap<String, Object>();
        chart.put("xAxisData", xAxisData);
        chart.put("expectedData", dischargeData);
        chart.put("actualData", chargeData);
        chart.put("seriesNames", Arrays.asList("放电功率(kW)", "充电功率(kW)"));
        return chart;
    }

    private boolean containsGenerationMetric(Set<String> metricKeys)
    {
        if (metricKeys == null)
        {
            return false;
        }
        for (String metricKey : metricKeys)
        {
            if (isGenerationMetric(metricKey))
            {
                return true;
            }
        }
        return false;
    }

    private boolean isGenerationMetric(String metricKey)
    {
        return "generation".equals(metricKey) || "generationKwh".equals(metricKey) || "generation_kwh".equals(metricKey);
    }

    private boolean isPowerMetric(String metricKey)
    {
        return "activePower".equals(metricKey) || "activePowerKw".equals(metricKey)
                || "active_power_kw".equals(metricKey) || "power".equals(metricKey);
    }

    private List<String> trendSeriesNames(Set<String> actualSet, Set<String> expectedSet)
    {
        List<String> names = new ArrayList<String>();
        if (expectedSet != null && !expectedSet.isEmpty())
        {
            names.add(containsGenerationMetric(expectedSet) ? "发电量(kWh/5min)" : "参考值");
        }
        if (actualSet != null && !actualSet.isEmpty())
        {
            names.add(containsPowerMetric(actualSet) ? "实时功率(kW)" : "采样值");
        }
        return names;
    }

    private boolean containsPowerMetric(Set<String> metricKeys)
    {
        if (metricKeys == null)
        {
            return false;
        }
        for (String metricKey : metricKeys)
        {
            if (isPowerMetric(metricKey))
            {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> stationDevices(Long stationId, List<Map<String, Object>> devices)
    {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> device : devices)
        {
            if (stationId != null && stationId.equals(EmsViewValueSupport.asLong(device.get("stationId"))))
            {
                result.add(new LinkedHashMap<String, Object>(device));
            }
        }
        return result;
    }

    private Map<String, Object> emptyChart()
    {
        Map<String, Object> chart = new LinkedHashMap<String, Object>();
        chart.put("xAxisData", Collections.emptyList());
        chart.put("actualData", Collections.emptyList());
        chart.put("expectedData", Collections.emptyList());
        return chart;
    }

    public BigDecimal deviceMetricValue(Long deviceId, String metricKey)
    {
        if (deviceId == null || StringUtils.isEmpty(metricKey))
        {
            return BigDecimal.ZERO;
        }
        BigDecimal value = viewMapper.selectDeviceMetricValue(deviceId, metricKey);
        return value == null ? BigDecimal.ZERO : value;
    }

    public BigDecimal deviceMetricValueOrNull(Long deviceId, String metricKey)
    {
        if (deviceId == null || StringUtils.isEmpty(metricKey))
        {
            return null;
        }
        return viewMapper.selectDeviceMetricValue(deviceId, metricKey);
    }

    public BigDecimal deviceMetricValue(Long deviceId, List<String> metricKeys)
    {
        if (metricKeys == null)
        {
            return BigDecimal.ZERO;
        }
        for (String metricKey : metricKeys)
        {
            BigDecimal value = deviceMetricValue(deviceId, metricKey);
            if (value.compareTo(BigDecimal.ZERO) != 0)
            {
                return value;
            }
        }
        return BigDecimal.ZERO;
    }

    private Map<String, Object> queryMap(Map<String, String> query)
    {
        return query == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(query);
    }
}
