package com.witos.ems.server.service.impl;

import com.witos.ems.server.service.EmsHomeService;
import com.witos.ems.server.support.EmsBusinessParamTemplate;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EmsHomeServiceImpl implements EmsHomeService
{
    @Resource
    private EmsViewReadSupport readSupport;

    @Resource
    private EmsBusinessParamResolver businessParamResolver;

    @Override
    public Map<String, Object> buildView(String variant, Map<String, String> query)
    {
        List<Map<String, Object>> stations = readSupport.stations(query);
        List<Map<String, Object>> alarms = readSupport.currentAlarms(query);
        List<Map<String, Object>> devices = readSupport.devices(query);
        Map<Long, BigDecimal> revenueMap = readSupport.stationRevenueMap(stations);
        Map<Long, Map<String, Object>> revenueQualityMap = readSupport.stationRevenueQualityMap(stations);
        Map<Long, Map<String, Object>> metricMap = readSupport.stationHomeMetricMap(stations);
        Map<Long, Map<String, Object>> syncStatusMap = readSupport.stationSyncStatusMap(stations);
        Long companyId = EmsViewValueSupport.asLong(query == null ? null : query.get("companyId"));
        Long stationId = EmsViewValueSupport.asLong(query == null ? null : query.get("stationId"));

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("summaryCards", Arrays.asList(
                card("电站数量", stations.size(), "座", "当前租户"),
                card("在线设备", countDevicesByComm(devices, "ONLINE"), "台", "实时统计"),
                card("今日发电", sumStationMetric(stations, metricMap, "todayGenerationKwh"), "kWh", "当前累计"),
                card("今日收益", sumStationRevenue(stations, revenueMap), "元", "当前累计")));
        result.put("stationRows", buildHomeStationRows(stations, metricMap, revenueMap, revenueQualityMap, syncStatusMap));
        result.put("alarmRows", buildAlarmRows(alarms));
        result.put("businessParams", businessParamResolver.resolveCoreValues(
                resolveStationTenantId(stations, stationId), companyId, stationId));
        result.put("statusRows", Arrays.asList(
                statusRow("在线", countDevicesByComm(devices, "ONLINE")),
                statusRow("离线", Math.max(0, devices.size() - countDevicesByComm(devices, "ONLINE")))));
        result.put("chartData", readSupport.stationDailyTrendChart(stations));
        return result;
    }

    private List<Map<String, Object>> buildHomeStationRows(List<Map<String, Object>> stations, Map<Long, Map<String, Object>> metricMap,
                                                           Map<Long, BigDecimal> revenueMap, Map<Long, Map<String, Object>> revenueQualityMap,
                                                           Map<Long, Map<String, Object>> syncStatusMap)
    {
        List<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
        for (Map<String, Object> station : stations)
        {
            Long stationId = EmsViewValueSupport.asLong(station.get("stationId"));
            Long companyId = EmsViewValueSupport.asLong(station.get("companyId"));
            Map<String, Object> metrics = metricMap.get(stationId);
            BigDecimal todayGeneration = metric(metrics, "todayGenerationKwh", station.get("todayGeneration"));
            BigDecimal todayRevenue = revenueMap.containsKey(stationId) ? revenueMap.get(stationId) : BigDecimal.ZERO;
            BigDecimal yearGeneration = metric(metrics, "yearGenerationKwh", station.get("yearGeneration"));
            BigDecimal capacityKw = EmsViewValueSupport.asBigDecimal(station.get("capacityKw"));
            BigDecimal currentPower = metric(metrics, "currentPowerKw", station.get("currentPower"));
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("stationId", stationId);
            row.put("companyId", companyId);
            row.put("stationName", station.get("stationName"));
            row.put("stationCode", station.get("stationCode"));
            row.put("companyName", station.get("companyName"));
            row.put("country", station.get("country"));
            row.put("address", station.get("address"));
            row.put("longitude", station.get("longitude"));
            row.put("latitude", station.get("latitude"));
            row.put("commissionDate", station.get("commissionDate"));
            row.put("capacityKw", capacityKw);
            row.put("stationType", station.get("stationType"));
            row.put("status", station.get("status"));
            row.put("edgeId", station.get("edgeId"));
            row.put("edgeName", station.get("edgeName"));
            row.put("edgeBinding", station.get("edgeBinding"));
            row.put("todayGeneration", todayGeneration);
            row.put("yearGeneration", yearGeneration);
            row.put("todayRevenue", todayRevenue);
            enrichRevenueStatus(row, stationId, revenueMap, revenueQualityMap);
            row.put("currentPower", currentPower);
            row.put("totalGeneration", metric(metrics, "totalGenerationKwh", station.get("totalGeneration")));
            row.put("storageCapacity", metric(metrics, "storageCapacity", station.get("storageCapacity")));
            row.put("todayChargeKwh", metric(metrics, "todayChargeKwh", station.get("todayChargeKwh")));
            row.put("todayDischargeKwh", metric(metrics, "todayDischargeKwh", station.get("todayDischargeKwh")));
            row.put("totalChargeKwh", metric(metrics, "totalChargeKwh", station.get("totalChargeKwh")));
            row.put("totalDischargeKwh", metric(metrics, "totalDischargeKwh", station.get("totalDischargeKwh")));
            Long tenantId = EmsViewValueSupport.asLong(station.get("tenantId"));
            row.put("co2Factor", businessParamResolver.resolveDecimal(EmsBusinessParamTemplate.CO2_FACTOR, tenantId, companyId, stationId));
            row.put("coalFactor", businessParamResolver.resolveDecimal(EmsBusinessParamTemplate.STANDARD_COAL_FACTOR, tenantId, companyId, stationId));
            row.put("treeFactor", businessParamResolver.resolveDecimal(EmsBusinessParamTemplate.TREE_FACTOR, tenantId, companyId, stationId));
            enrichSyncStatus(row, stationId, syncStatusMap);
            rows.add(row);
        }
        return rows;
    }

    private void enrichSyncStatus(Map<String, Object> row, Long stationId, Map<Long, Map<String, Object>> syncStatusMap)
    {
        Map<String, Object> status = syncStatusMap.get(stationId);
        if (status == null)
        {
            row.put("syncStatus", "MISSING");
            row.put("syncMessage", "开源版设备数据由前端模拟");
            row.put("latestSampleTime", "");
            row.put("lastSyncAttemptTime", "");
            return;
        }
        row.put("syncStatus", status.get("syncStatus"));
        row.put("syncMessage", status.get("syncMessage"));
        row.put("latestSampleTime", status.get("latestSampleTime"));
        row.put("lastSyncAttemptTime", status.get("lastAttemptTime"));
    }

    private void enrichRevenueStatus(Map<String, Object> row, Long stationId, Map<Long, BigDecimal> revenueMap,
                                     Map<Long, Map<String, Object>> revenueQualityMap)
    {
        Map<String, Object> quality = revenueQualityMap.get(stationId);
        if (quality == null || !revenueMap.containsKey(stationId))
        {
            row.put("revenueStatus", "MISSING");
            row.put("revenueMessage", "当日电站报表未生成，今日收益待补拉或电价配置待检查");
            return;
        }
        Object message = quality.get("revenueMessage");
        if (message != null && !String.valueOf(message).trim().isEmpty())
        {
            row.put("revenueStatus", "WARN");
            row.put("revenueMessage", message);
            return;
        }
        row.put("revenueStatus", "OK");
        row.put("revenueMessage", "");
    }

    private List<Map<String, Object>> buildAlarmRows(List<Map<String, Object>> alarms)
    {
        List<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
        for (Map<String, Object> alarm : alarms)
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("time", alarm.get("time"));
            row.put("companyId", alarm.get("companyId"));
            row.put("stationId", alarm.get("stationId"));
            row.put("deviceId", alarm.get("deviceId"));
            row.put("stationName", alarm.get("stationName"));
            row.put("deviceName", alarm.get("deviceName"));
            row.put("severity", alarm.get("severity"));
            row.put("message", alarm.get("message"));
            rows.add(row);
        }
        return rows;
    }

    private int countDevicesByComm(List<Map<String, Object>> devices, String commStatus)
    {
        int count = 0;
        for (Map<String, Object> device : devices)
        {
            if (commStatus.equalsIgnoreCase(String.valueOf(device.get("commStatus"))))
            {
                count++;
            }
        }
        return count;
    }

    private BigDecimal sumStationRevenue(List<Map<String, Object>> stations, Map<Long, BigDecimal> revenueMap)
    {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> station : stations)
        {
            Long stationId = EmsViewValueSupport.asLong(station.get("stationId"));
            sum = sum.add(revenueMap.containsKey(stationId) ? revenueMap.get(stationId) : BigDecimal.ZERO);
        }
        return sum;
    }

    private BigDecimal sumStationMetric(List<Map<String, Object>> stations, Map<Long, Map<String, Object>> metricMap, String metricKey)
    {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> station : stations)
        {
            Long stationId = EmsViewValueSupport.asLong(station.get("stationId"));
            sum = sum.add(metric(metricMap.get(stationId), metricKey, BigDecimal.ZERO));
        }
        return sum;
    }

    private BigDecimal metric(Map<String, Object> metrics, String key, Object fallback)
    {
        if (metrics != null && metrics.containsKey(key))
        {
            return EmsViewValueSupport.asDecimal(metrics.get(key));
        }
        return EmsViewValueSupport.asDecimal(fallback);
    }

    private Long resolveStationTenantId(List<Map<String, Object>> stations, Long stationId)
    {
        if (stationId != null)
        {
            for (Map<String, Object> station : stations)
            {
                Long candidate = EmsViewValueSupport.asLong(station.get("stationId"));
                if (stationId.equals(candidate))
                {
                    Long tenantId = EmsViewValueSupport.asLong(station.get("tenantId"));
                    if (tenantId != null)
                    {
                        return tenantId;
                    }
                    break;
                }
            }
        }
        return EmsRequestSupport.currentTenantId();
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

    private Map<String, Object> statusRow(String label, Object value)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("label", label);
        row.put("value", value);
        return row;
    }

}
