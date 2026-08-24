package com.witos.ems.server.service.impl;

import com.witos.ems.server.service.EmsOpsService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EmsOpsServiceImpl implements EmsOpsService
{
    @Resource
    private EmsViewReadSupport readSupport;

    @Override
    public Map<String, Object> status(Map<String, String> query)
    {
        List<Map<String, Object>> stations = readSupport.stations(query);
        List<Map<String, Object>> alarms = readSupport.currentAlarms(query);
        List<Map<String, Object>> devices = readSupport.devices(query);
        readSupport.enrichDevicesWithRealtimeQuality(devices);
        Map<Long, BigDecimal> generationMap = readSupport.stationGenerationMap(stations);
        Map<Long, Map<String, Object>> metricMap = readSupport.stationHomeMetricMap(stations);
        Map<Long, String> latestSampleTimes = readSupport.latestSampleTimeMap(stations);
        List<Map<String, Object>> stationRows = filterStationRows(buildOpsStationRows(stations, devices, alarms, generationMap, metricMap, latestSampleTimes), query);
        String latestSampleTime = latestOverallSampleTime(stationRows);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("summaryCards", Arrays.asList(
                card("在线电站", countRowStatus(stationRows, "ONLINE"), "座", "当前筛选范围"),
                card("离线电站", countRowStatus(stationRows, "OFFLINE"), "座", "当前筛选范围"),
                card("活动告警", countRowStatus(stationRows, "ALARM"), "条", "当前筛选范围"),
                card("最新上报", latestSampleTime, "", "最新采样时间")));
        Map<String, Object> chartData = readSupport.stationDailyTrendChart(stations);
        result.put("chartData", chartData);
        result.put("chartXAxis", chartData.get("xAxisData"));
        result.put("chartSeriesNames", Arrays.asList("发电量", "收益"));
        result.put("stationRows", stationRows);
        result.put("deviceTree", buildDeviceTree(query, stationRows, devices));
        result.put("alarmRows", buildAlarmRows(alarms));
        return result;
    }

    private Map<Long, List<Map<String, Object>>> buildDeviceTree(Map<String, String> query, List<Map<String, Object>> stationRows,
                                                                 List<Map<String, Object>> devices)
    {
        Map<Long, List<Map<String, Object>>> result = new LinkedHashMap<Long, List<Map<String, Object>>>();
        Long queryStationId = EmsViewValueSupport.asLong(query == null ? null : query.get("stationId"));
        for (Map<String, Object> station : stationRows)
        {
            Long stationId = EmsViewValueSupport.asLong(station.get("stationId"));
            if (queryStationId != null && !queryStationId.equals(stationId))
            {
                continue;
            }
            result.put(stationId, readSupport.deviceHierarchyTree(stationId, devices));
        }
        return result;
    }

    private List<Map<String, Object>> buildOpsStationRows(List<Map<String, Object>> stations,
                                                          List<Map<String, Object>> devices,
                                                          List<Map<String, Object>> alarms,
                                                          Map<Long, BigDecimal> generationMap,
                                                          Map<Long, Map<String, Object>> metricMap,
                                                          Map<Long, String> latestSampleTimes)
    {
        List<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
        for (Map<String, Object> station : stations)
        {
            Long stationId = EmsViewValueSupport.asLong(station.get("stationId"));
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("companyId", EmsViewValueSupport.asLong(station.get("companyId")));
            row.put("companyName", station.get("companyName"));
            row.put("stationId", stationId);
            row.put("stationName", station.get("stationName"));
            row.put("capacityKw", station.get("capacityKw"));
            row.put("longitude", station.get("longitude"));
            row.put("latitude", station.get("latitude"));
            row.put("status", stationStatus(station, stationId, devices, alarms));
            row.put("latestDataTime", latestSampleTimes.containsKey(stationId) ? latestSampleTimes.get(stationId) : "");
            row.put("deviceCount", countStationDevices(stationId, devices));
            row.put("alarmCount", countStationAlarms(stationId, alarms));
            row.put("todayGeneration", generationMap.containsKey(stationId) ? generationMap.get(stationId) : BigDecimal.ZERO);
            Map<String, Object> metrics = metricMap.get(stationId);
            row.put("currentPower", metrics != null && metrics.containsKey("currentPowerKw")
                    ? EmsViewValueSupport.asDecimal(metrics.get("currentPowerKw")) : BigDecimal.ZERO);
            row.put("commStatus", stationCommStatus(stationId, devices));
            rows.add(row);
        }
        return rows;
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
            row.put("alarmName", alarm.get("alarmName"));
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

    private int countRowStatus(List<Map<String, Object>> rows, String status)
    {
        int count = 0;
        for (Map<String, Object> row : rows)
        {
            if (status.equalsIgnoreCase(String.valueOf(row.get("status"))))
            {
                count++;
            }
        }
        return count;
    }

    private String stationStatus(Map<String, Object> station, Long stationId, List<Map<String, Object>> devices, List<Map<String, Object>> alarms)
    {
        String stationState = String.valueOf(station.get("status"));
        if ("DISABLED".equalsIgnoreCase(stationState))
        {
            return "OFFLINE";
        }
        if ("CONSTRUCTION".equalsIgnoreCase(stationState))
        {
            return "FAULT";
        }
        if (countStationAlarms(stationId, alarms) > 0)
        {
            return "ALARM";
        }
        int online = 0;
        int total = 0;
        for (Map<String, Object> device : devices)
        {
            if (stationId != null && stationId.equals(EmsViewValueSupport.asLong(device.get("stationId"))))
            {
                total++;
                if ("ONLINE".equalsIgnoreCase(String.valueOf(device.get("commStatus"))))
                {
                    online++;
                }
            }
        }
        if (total == 0 || online == 0)
        {
            return "OFFLINE";
        }
        if (online < total)
        {
            return "FAULT";
        }
        return "ONLINE";
    }

    private List<Map<String, Object>> filterStationRows(List<Map<String, Object>> rows, Map<String, String> query)
    {
        String statusType = query == null ? null : query.get("statusType");
        if (statusType == null || statusType.trim().isEmpty())
        {
            return rows;
        }
        List<Map<String, Object>> filtered = new java.util.ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows)
        {
            if (statusType.equalsIgnoreCase(String.valueOf(row.get("status"))))
            {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private int countStationDevices(Long stationId, List<Map<String, Object>> devices)
    {
        int count = 0;
        for (Map<String, Object> device : devices)
        {
            if (stationId != null && stationId.equals(EmsViewValueSupport.asLong(device.get("stationId"))))
            {
                count++;
            }
        }
        return count;
    }

    private int countStationAlarms(Long stationId, List<Map<String, Object>> alarms)
    {
        int count = 0;
        for (Map<String, Object> alarm : alarms)
        {
            if (stationId != null && stationId.equals(EmsViewValueSupport.asLong(alarm.get("stationId"))))
            {
                count++;
            }
        }
        return count;
    }

    private String stationCommStatus(Long stationId, List<Map<String, Object>> devices)
    {
        int online = 0;
        int total = 0;
        for (Map<String, Object> device : devices)
        {
            if (stationId != null && stationId.equals(EmsViewValueSupport.asLong(device.get("stationId"))))
            {
                total++;
                if ("ONLINE".equalsIgnoreCase(String.valueOf(device.get("commStatus"))))
                {
                    online++;
                }
            }
        }
        if (total == 0)
        {
            return "无设备";
        }
        if (online == total)
        {
            return "正常";
        }
        if (online == 0)
        {
            return "全部离线";
        }
        return "部分离线";
    }

    private String latestOverallSampleTime(List<Map<String, Object>> stationRows)
    {
        if (stationRows == null || stationRows.isEmpty())
        {
            return "--";
        }
        String latest = "--";
        for (Map<String, Object> row : stationRows)
        {
            String sampleTime = row == null ? null : String.valueOf(row.get("latestDataTime"));
            if (sampleTime != null && !sampleTime.trim().isEmpty() && ("--".equals(latest) || sampleTime.compareTo(latest) > 0))
            {
                latest = sampleTime;
            }
        }
        return latest;
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
}
