package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.security.utils.SecurityUtils;
import com.witos.ems.server.config.EmsMetricProperties;
import com.witos.ems.server.domain.entity.EmsEnergyFlowSession;
import com.witos.ems.server.domain.entity.EmsOptimizerComponentBinding;
import com.witos.ems.server.domain.entity.EmsStationViewNode;
import com.witos.ems.server.domain.entity.EmsStationViewRelation;
import com.witos.ems.server.domain.entity.EmsStationViewTab;
import com.witos.ems.server.mapper.EmsEnergyFlowSessionMapper;
import com.witos.ems.server.mapper.EmsOptimizerComponentBindingMapper;
import com.witos.ems.server.mapper.EmsStationViewNodeMapper;
import com.witos.ems.server.mapper.EmsStationViewRelationMapper;
import com.witos.ems.server.mapper.EmsStationViewTabMapper;
import com.witos.ems.server.support.EmsBusinessParamTemplate;
import com.witos.ems.server.support.EmsRequestSupport;
import com.witos.ems.server.service.EmsMonitorService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class EmsMonitorServiceImpl implements EmsMonitorService
{
    private static final String VIEW_TYPE_PHYSICAL = "PHYSICAL";
    private static final String VIEW_TYPE_LOGIC = "LOGIC";
    private static final ZoneId HISTORY_TIME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HISTORY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private EmsViewReadSupport readSupport;

    @Resource
    private EmsBusinessParamResolver businessParamResolver;

    @Resource
    private EmsEnergyFlowSessionMapper energyFlowSessionMapper;

    @Resource
    private EmsStationViewTabMapper stationViewTabMapper;

    @Resource
    private EmsStationViewNodeMapper stationViewNodeMapper;

    @Resource
    private EmsStationViewRelationMapper stationViewRelationMapper;

    @Resource
    private EmsOptimizerComponentBindingMapper optimizerComponentBindingMapper;

    @Resource
    private EmsMetricProperties metricProperties;

    @Override
    public Map<String, Object> overview(Map<String, String> query)
    {
        List<Map<String, Object>> stations = readSupport.stations(query);
        List<Map<String, Object>> alarms = readSupport.currentAlarms(query);
        List<Map<String, Object>> devices = readSupport.devices(query);
        readSupport.enrichDevicesWithRealtimeQuality(devices);
        Map<Long, BigDecimal> generationMap = readSupport.stationGenerationMap(stations);
        Map<Long, BigDecimal> revenueMap = readSupport.stationRevenueMap(stations);
        Map<Long, Map<String, Object>> revenueQualityMap = readSupport.stationRevenueQualityMap(stations);
        Map<Long, Map<String, Object>> metricMap = readSupport.stationHomeMetricMap(stations);
        Map<Long, String> latestSampleTimes = readSupport.latestSampleTimeMap(stations);
        Map<Long, Map<String, Object>> syncStatusMap = readSupport.stationSyncStatusMap(stations);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        int onlineCount = countDevicesByComm(devices, "ONLINE");
        int offlineCount = Math.max(0, devices.size() - onlineCount);
        BigDecimal todayGeneration = sumStationGeneration(stations, generationMap);
        BigDecimal todayRevenue = sumStationRevenue(stations, revenueMap);
        summary.put("a", onlineCount);
        summary.put("b", offlineCount);
        summary.put("c", alarms.size());
        summary.put("d", todayGeneration);
        summary.put("stationCount", stations.size());
        summary.put("onlineDeviceCount", onlineCount);
        summary.put("offlineDeviceCount", offlineCount);
        summary.put("activeAlarmCount", alarms.size());
        summary.put("todayGeneration", todayGeneration);
        summary.put("todayRevenue", todayRevenue);
        result.put("summary", summary);
        result.put("stations", enrichStationRevenue(buildOpsStationRows(stations, devices, alarms, generationMap, latestSampleTimes, syncStatusMap), revenueMap, revenueQualityMap));
        result.put("alarms", buildAlarmRows(alarms));
        Map<String, Object> selectedStation = resolveSelectedStation(query, stations, generationMap, revenueMap, revenueQualityMap,
            metricMap, latestSampleTimes, syncStatusMap, alarms, devices);
        result.put("selectedStation", selectedStation);
        result.put("stationDevices", buildStationDevices(selectedStation, devices));
        result.put("realtimeMetrics", buildRealtimeMetrics(selectedStation, devices));
        result.put("energyFlow", buildEnergyFlow(selectedStation, devices));
        result.put("sessionControl", buildSessionControl(selectedStation));
        result.put("trend", readSupport.stationRealtimeTrendChart(stations));
        return result;
    }

    @Override
    public Map<String, Object> deviceDetail(Long deviceId)
    {
        if (deviceId == null)
        {
            return emptyDeviceDetail();
        }
        Map<String, Object> device = readSupport.device(deviceId);
        if (device.isEmpty())
        {
            return emptyDeviceDetail();
        }
        readSupport.enrichDevicesWithRealtimeQuality(Arrays.asList(device));

        Map<Long, Integer> alarmCountMap = readSupport.deviceAlarmCountMap(Arrays.asList(device));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Object> detail = new LinkedHashMap<String, Object>(device);
        BigDecimal activePower = normalizeMetric(readSupport.deviceMetricValue(deviceId, "activePower"));
        BigDecimal todayGeneration = normalizeMetric(readSupport.deviceTodayGeneration(deviceId));
        BigDecimal temperature = normalizeMetric(readSupport.deviceMetricValue(deviceId, "temperature"));
        BigDecimal voltage = normalizeMetric(readSupport.deviceMetricValue(deviceId, "voltage"));
        BigDecimal current = normalizeMetric(readSupport.deviceMetricValue(deviceId, "current"));
        detail.put("activePower", activePower);
        detail.put("todayGeneration", todayGeneration);
        detail.put("temperature", temperature);
        detail.put("voltage", voltage);
        detail.put("current", current);

        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        boolean online = "ONLINE".equalsIgnoreCase(String.valueOf(device.get("commStatus")));
        int alarmCount = alarmCountMap.containsKey(deviceId) ? alarmCountMap.get(deviceId) : 0;
        summary.put("a", online ? 1 : 0);
        summary.put("b", online ? 0 : 1);
        summary.put("c", alarmCount);
        summary.put("d", todayGeneration);
        result.put("summary", summary);
        result.put("detail", detail);
        result.put("identityRows", Arrays.asList(
                detailRow("公司", textValue(device.get("companyName")), "所属公司"),
                detailRow("电站", textValue(device.get("stationName")), "所属电站"),
                detailRow("设备名称", textValue(device.get("deviceName")), "页面展示名称"),
                detailRow("设备编码", textValue(device.get("deviceCode")), "设备唯一编码"),
                detailRow("设备类型", textValue(device.get("deviceType")), "设备分类"),
                detailRow("组件绑定", textValue(device.get("componentBinding")), "绑定的站端组件")));
        result.put("assetRows", Arrays.asList(
                detailRow("厂家", textValue(device.get("manufacturer")), "设备制造商"),
                detailRow("型号", textValue(device.get("model")), "设备型号"),
                detailRow("序列号", textValue(device.get("serialNo")), "出厂序列号"),
                detailRow("固件版本", textValue(device.get("firmwareVersion")), "当前固件版本"),
                detailRow("控制器", textValue(device.get("controllerName")), "上级控制器"),
                detailRow("安装日期", textValue(device.get("installDate")), "设备投运信息")));
        result.put("metricRows", Arrays.asList(
                detailRow("实时功率", metricValue(activePower, "kW"), "当前采样值"),
                detailRow("今日发电", metricValue(todayGeneration, "kWh"), "当日累计"),
                detailRow("温度", metricValue(temperature, "°C"), "最近一次采样"),
                detailRow("电压", metricValue(voltage, "V"), "最近一次采样"),
                detailRow("电流", metricValue(current, "A"), "最近一次采样"),
                detailRow("活动告警", alarmCount + " 条", "当前未恢复告警数")));
        result.put("statusRows", Arrays.asList(
                detailRow("通讯状态", textValue(device.get("commStatus")), "链路状态"),
                detailRow("设备状态", textValue(device.get("status")), "业务状态"),
                detailRow("最近心跳", textValue(device.get("lastHeartbeatTime")), "设备最近在线时间"),
                detailRow("安装位置", textValue(device.get("componentAlias")), "站内安装位置")));
        result.put("trend", readSupport.deviceMetricTrendChart(deviceId,
            Arrays.asList("activePower", "activePowerKw", "active_power_kw", "power"),
            Arrays.asList("generation", "generationKwh", "generation_kwh")));
        return result;
    }

    @Override
    public Map<String, Object> deviceDetail(Map<String, String> query)
    {
        Long deviceId = EmsViewValueSupport.asLong(query == null ? null : query.get("deviceId"));
        if (deviceId == null)
        {
            List<Map<String, Object>> devices = readSupport.devices(query);
            if (!devices.isEmpty())
            {
                deviceId = EmsViewValueSupport.asLong(devices.get(0).get("deviceId"));
            }
        }
        return deviceDetail(deviceId);
    }

    @Override
    public Map<String, Object> storageDetail(Long deviceId)
    {
        if (deviceId == null)
        {
            return emptyStorageDetail();
        }
        Map<String, Object> device = readSupport.device(deviceId);
        if (device.isEmpty())
        {
            return emptyStorageDetail();
        }
        readSupport.enrichDevicesWithRealtimeQuality(Arrays.asList(device));

        Map<String, Object> detail = new LinkedHashMap<String, Object>(device);
        BigDecimal soc = normalizeMetric(readSupport.deviceMetricValue(deviceId, "soc"));
        BigDecimal soh = normalizeNullableMetric(readSupport.deviceMetricValueOrNull(deviceId, "soh"));
        BigDecimal activePower = normalizeMetric(readSupport.deviceMetricValue(deviceId, "activePower"));
        BigDecimal chargePower = activePower.signum() < 0 ? activePower.abs() : BigDecimal.ZERO;
        BigDecimal dischargePower = activePower.signum() > 0 ? activePower : BigDecimal.ZERO;
        BigDecimal chargeEnergy = normalizeMetric(readSupport.deviceMetricValue(deviceId, "chargeKwh"));
        BigDecimal dischargeEnergy = normalizeMetric(readSupport.deviceMetricValue(deviceId, "dischargeKwh"));
        BigDecimal busVoltage = normalizeMetric(readSupport.deviceMetricValue(deviceId, "voltage"));
        Map<Long, Integer> alarmCountMap = readSupport.deviceAlarmCountMap(Arrays.asList(device));
        int alarmCount = alarmCountMap.containsKey(deviceId) ? alarmCountMap.get(deviceId) : 0;

        detail.put("soc", soc);
        detail.put("soh", soh);
        detail.put("chargePower", chargePower);
        detail.put("dischargePower", dischargePower);
        detail.put("chargeEnergy", chargeEnergy);
        detail.put("dischargeEnergy", dischargeEnergy);
        detail.put("busVoltage", busVoltage);

        Object ratedCapacity = device.get("ratedCapacity");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("a", soc);
        summary.put("b", soh);
        summary.put("c", chargePower);
        summary.put("d", dischargePower);
        result.put("summary", summary);
        result.put("detail", detail);
        result.put("tabs", Arrays.asList(
                tab("overview", "概览"),
                tab("detail", "详细信息"),
                tab("alarm", "告警管理"),
                tab("history", "历史信息"),
                tab("config", "配置")));
        result.put("realtimeMetrics", Arrays.asList(
                detailRow("电池运行状态", textValue(device.get("status")), "运行"),
                detailRow("充放电模式", "--", "远程控制"),
                detailRow("额定容量", ratedCapacity == null ? "--" : ratedCapacity + " 度", "额定容量"),
                detailRow("备电时间", "--", "备电时间"),
                detailRow("当日充电量", metricValue(chargeEnergy, "度"), "当日累计"),
                detailRow("当日放电量", metricValue(dischargeEnergy, "度"), "当日累计"),
                detailRow("充放电功率", metricValue(chargePower, "kW"), "实时充放电"),
                detailRow("母线电压", metricValue(busVoltage, "V"), "最近采样"),
                detailRow("SOC", metricValue(soc, "%"), "荷电状态")));
        result.put("healthRows", Arrays.asList(
                detailRow("电池健康检测状态", "待检测", "电池健康检测"),
                detailRow("SOH", nullableMetricValue(soh, "%"), "健康状态")));
        result.put("identityRows", Arrays.asList(
                detailRow("设备名称", textValue(device.get("deviceName")), "页面展示名称"),
                detailRow("型号", textValue(device.get("model")), "设备型号"),
                detailRow("设备类型", textValue(device.get("deviceType")), "设备分类"),
                detailRow("换机历史", "--", "换机记录")));
        result.put("storageUnits", Collections.emptyList());
        result.put("batteryPacks", Collections.emptyList());
        result.put("statusRows", Arrays.asList(
                detailRow("SOC", soc + " %", "荷电状态"),
                detailRow("SOH", nullableMetricValue(soh, "%"), "健康状态"),
                detailRow("充电功率", chargePower + " kW", "实时充电"),
                detailRow("放电功率", dischargePower + " kW", "实时放电"),
                detailRow("今日充电量", chargeEnergy + " kWh", "当日累计"),
                detailRow("今日放电量", dischargeEnergy + " kWh", "当日累计"),
                detailRow("活动告警", alarmCount + " 条", "当前未恢复告警数")));
        result.put("socTrend", readSupport.deviceMetricTrendChart(deviceId, Arrays.asList("soc", "avgSoc"), Arrays.asList("soh", "avgSoh")));
        result.put("powerTrend", readSupport.deviceStoragePowerTrendChart(deviceId));
        return result;
    }

    @Override
    public Map<String, Object> storageHistory(Long deviceId, Map<String, String> query)
    {
        Map<String, String> params = query == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(query);
        if (deviceId != null)
        {
            params.put("deviceId", String.valueOf(deviceId));
        }
        return history(params);
    }

    @Override
    public Map<String, Object> startEnergyFlowSession(Map<String, Object> body)
    {
        Long stationId = EmsRequestSupport.requiredLong(body, "stationId", "电站不能为空");
        Map<String, Object> station = readSupport.station(stationId);
        if (station.isEmpty())
        {
            throw new ServiceException("电站不存在或无权限");
        }

        Map<String, Object> sessionControl = buildSessionControl(station);
        int remainingCount = asInteger(sessionControl.get("remainingCount"));
        if (remainingCount <= 0)
        {
            throw new ServiceException("已超过本月实时能量流开启次数限制");
        }

        int sessionSeconds = asInteger(sessionControl.get("sessionSeconds"));
        Date startedAt = new Date();
        Date expiresAt = new Date(startedAt.getTime() + sessionSeconds * 1000L);
        EmsEnergyFlowSession session = new EmsEnergyFlowSession();
        session.setTenantId(EmsViewValueSupport.asLong(station.get("tenantId")));
        session.setCompanyId(EmsViewValueSupport.asLong(station.get("companyId")));
        session.setStationId(stationId);
        session.setUserId(SecurityUtils.getUserId());
        session.setSessionSeconds(sessionSeconds);
        session.setStartedAt(startedAt);
        session.setExpiresAt(expiresAt);
        session.setSessionStatus("ACTIVE");
        session.setRequestSource(EmsRequestSupport.defaultString(body.get("requestSource"), "PC"));
        session.setCreateBy(EmsRequestSupport.currentUsername());
        energyFlowSessionMapper.insert(session);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessionId", session.getId());
        result.put("stationId", stationId);
        result.put("sessionSeconds", sessionSeconds);
        result.put("startedAt", startedAt);
        result.put("expiresAt", expiresAt);
        result.put("remainingCount", remainingCount - 1);
        result.put("monthlyLimit", sessionControl.get("monthlyLimit"));
        return result;
    }

    @Override
    public Map<String, Object> history(Map<String, String> query)
    {
        Map<String, String> params = query == null
                ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(query);
        Date now = new Date();
        Date endTime = historyTime(params.get("endTime"), now);
        Date startTime = historyTime(params.get("startTime"), new Date(endTime.getTime() - 24L * 60 * 60 * 1000));
        validateHistoryRange(startTime, endTime, now);

        Long deviceId = EmsViewValueSupport.asLong(params.get("deviceId"));
        List<Long> deviceIds = historyDeviceIds(params, deviceId);
        String metricKey = params.get("metricKey");
        if (metricKey == null || metricKey.trim().isEmpty())
        {
            metricKey = "activePower";
        }
        List<Map<String, Object>> rows = readSupport.deviceMetricHistory(
                deviceIds, metricKey.trim(), startTime, endTime);
        Map<String, BigDecimal> valuesByTime = new LinkedHashMap<String, BigDecimal>();
        List<Object> values = new ArrayList<Object>();
        for (Map<String, Object> row : rows)
        {
            String quality = String.valueOf(row.get("quality"));
            Object rawValue = row.get("value");
            if (!("GOOD".equalsIgnoreCase(quality) || "PARTIAL".equalsIgnoreCase(quality)) || rawValue == null)
            {
                continue;
            }
            BigDecimal value = EmsViewValueSupport.asDecimal(rawValue);
            String time = String.valueOf(row.get("time"));
            valuesByTime.put(time, valuesByTime.containsKey(time) ? valuesByTime.get(time).add(value) : value);
            values.add(value);
        }
        Map<String, Object> trend = new LinkedHashMap<String, Object>();
        trend.put("xAxisData", new ArrayList<String>(valuesByTime.keySet()));
        trend.put("actualData", new ArrayList<BigDecimal>(valuesByTime.values()));
        trend.put("expectedData", Collections.emptyList());
        trend.put("seriesNames", Arrays.asList("实际值", ""));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("recordCount", rows.size());
        summary.put("maxValue", maxValue(values, null));
        summary.put("minValue", minValue(values, null));
        result.put("summary", summary);
        result.put("historyRows", rows);
        result.put("trend", trend);
        result.put("startTime", startTime);
        result.put("endTime", endTime);
        result.put("retentionDays", metricProperties.getDetailRetentionDays());
        return result;
    }

    private Date historyTime(String value, Date defaultValue)
    {
        if (value == null || value.trim().isEmpty())
        {
            return defaultValue;
        }
        try
        {
            String normalized = value.trim().replace('T', ' ');
            if (normalized.matches("\\d{4}-\\d{2}-\\d{2}"))
            {
                normalized += " 00:00:00";
            }
            LocalDateTime localTime = LocalDateTime.parse(normalized, HISTORY_TIME_FORMATTER);
            return Date.from(localTime.atZone(HISTORY_TIME_ZONE).toInstant());
        }
        catch (DateTimeParseException ex)
        {
            throw new ServiceException("时间格式不正确，应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    private void validateHistoryRange(Date startTime, Date endTime, Date now)
    {
        if (startTime.after(endTime))
        {
            throw new ServiceException("开始时间不能晚于结束时间");
        }
        if (endTime.after(new Date(now.getTime() + 60 * 1000L)))
        {
            throw new ServiceException("结束时间不能晚于当前时间");
        }
        long retentionMillis = Math.max(metricProperties.getDetailRetentionDays(), 1) * 24L * 60 * 60 * 1000;
        if (startTime.before(new Date(now.getTime() - retentionMillis))
                || endTime.getTime() - startTime.getTime() > retentionMillis)
        {
            throw new ServiceException(historyRetentionMessage());
        }
    }

    private String historyRetentionMessage()
    {
        return "实时明细数据仅保留" + Math.max(metricProperties.getDetailRetentionDays(), 1)
                + "天，请到小时/日/月/年报表查询更早数据";
    }

    private List<Long> historyDeviceIds(Map<String, String> query, Long deviceId)
    {
        if (deviceId != null)
        {
            if (readSupport.device(deviceId).isEmpty())
            {
                throw new ServiceException("设备不存在或无权限");
            }
            return Collections.singletonList(deviceId);
        }
        List<Long> result = new ArrayList<Long>();
        for (Map<String, Object> device : readSupport.devices(query))
        {
            Long id = EmsViewValueSupport.asLong(device.get("deviceId"));
            if (id != null)
            {
                result.add(id);
            }
        }
        return result;
    }

    @Override
    public Map<String, Object> stationView(Map<String, String> query)
    {
        return attachViewPayload(overview(query), query, VIEW_TYPE_PHYSICAL);
    }

    @Override
    public Map<String, Object> logicView(Map<String, String> query)
    {
        return attachViewPayload(overview(query), query, VIEW_TYPE_LOGIC);
    }

    @Override
    public Map<String, Object> listViewTabs(Map<String, String> query)
    {
        Long stationId = EmsViewValueSupport.asLong(query == null ? null : query.get("stationId"));
        String viewType = normalizeViewType(query == null ? null : query.get("viewType"));
        Map<String, Object> station = requireStationAccess(stationId);
        List<EmsStationViewTab> tabs = listTabs(stationId, viewType);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("stationId", stationId);
        result.put("companyId", station.get("companyId"));
        result.put("viewType", viewType);
        result.put("tabs", tabs.stream().map(this::toTabMap).collect(Collectors.toList()));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveViewTab(Map<String, Object> body)
    {
        Long stationId = EmsRequestSupport.requiredLong(body, "stationId", "电站不能为空");
        String viewType = normalizeViewType(EmsRequestSupport.stringValue(body.get("viewType")));
        Map<String, Object> station = requireStationAccess(stationId);
        Long companyId = EmsViewValueSupport.asLong(station.get("companyId"));
        Long tenantId = EmsViewValueSupport.asLong(station.get("tenantId"));
        Long id = EmsRequestSupport.asLong(body.get("id"));
        String tabName = EmsRequestSupport.defaultString(body.get("tabName"), "默认视图").trim();
        if (tabName.isEmpty())
        {
            throw new ServiceException("页签名称不能为空");
        }

        EmsStationViewTab tab = id == null ? new EmsStationViewTab() : requireTabAccess(id, viewType, stationId);
        if (id != null && !java.util.Objects.equals(tab.getTenantId(), tenantId))
        {
            throw new ServiceException("页签与电站不属于同一租户");
        }
        tab.setTenantId(tenantId);
        tab.setCompanyId(companyId);
        tab.setStationId(stationId);
        tab.setViewType(viewType);
        tab.setTabName(tabName);
        tab.setSortNo(EmsRequestSupport.asInteger(body.get("sortNo"), nextSortNo(stationId, viewType)));
        tab.setStatus(EmsRequestSupport.defaultString(body.get("status"), "0"));
        tab.setRemark(EmsRequestSupport.stringValue(body.get("remark")));
        if (id == null)
        {
            stationViewTabMapper.insert(tab);
        }
        else
        {
            stationViewTabMapper.updateById(tab);
        }
        return toTabMap(tab);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeViewTab(Long id)
    {
        EmsStationViewTab tab = requireTabAccess(id, null, null);
        clearViewData(tab.getId());
        return stationViewTabMapper.deleteById(tab.getId()) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveStationView(Map<String, Object> body)
    {
        return saveView(body, VIEW_TYPE_PHYSICAL);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveLogicView(Map<String, Object> body)
    {
        return saveView(body, VIEW_TYPE_LOGIC);
    }

    private Map<String, Object> emptyDeviceDetail()
    {
        Map<String, Object> empty = new LinkedHashMap<String, Object>();
        empty.put("summary", new LinkedHashMap<String, Object>());
        empty.put("detail", new LinkedHashMap<String, Object>());
        empty.put("statusRows", Collections.emptyList());
        empty.put("trend", emptyChart());
        return empty;
    }

    private Map<String, Object> emptyDualChart()
    {
        Map<String, Object> chart = emptyChart();
        chart.put("secondData", Collections.emptyList());
        return chart;
    }

    private Map<String, Object> attachViewPayload(Map<String, Object> base, Map<String, String> query, String viewType)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>(base);
        Map<String, Object> selectedStation = castMap(result.get("selectedStation"));
        Long stationId = EmsViewValueSupport.asLong(selectedStation.get("stationId"));
        if (stationId == null)
        {
            result.put("viewType", viewType);
            result.put("viewTabs", Collections.emptyList());
            result.put("activeTab", new LinkedHashMap<String, Object>());
            result.put("viewNodes", Collections.emptyList());
            result.put("viewRelations", Collections.emptyList());
            result.put("optimizerBindings", Collections.emptyList());
            return result;
        }
        requireStationAccess(stationId);
        List<EmsStationViewTab> tabs = listTabs(stationId, viewType);
        Long tabId = EmsViewValueSupport.asLong(query == null ? null : query.get("tabId"));
        EmsStationViewTab activeTab = resolveActiveTab(tabs, tabId);

        result.put("viewType", viewType);
        result.put("viewTabs", tabs.stream().map(this::toTabMap).collect(Collectors.toList()));
        result.put("activeTab", activeTab == null ? new LinkedHashMap<String, Object>() : toTabMap(activeTab));
        result.put("viewNodes", activeTab == null ? Collections.emptyList() : listViewNodes(activeTab.getId()));
        result.put("viewRelations", activeTab == null ? Collections.emptyList() : listViewRelations(activeTab.getId()));
        result.put("optimizerBindings", activeTab == null ? Collections.emptyList() : listOptimizerBindings(activeTab.getId()));
        return result;
    }

    private Map<String, Object> saveView(Map<String, Object> body, String viewType)
    {
        Long stationId = EmsRequestSupport.requiredLong(body, "stationId", "电站不能为空");
        Map<String, Object> station = requireStationAccess(stationId);
        Long companyId = EmsViewValueSupport.asLong(station.get("companyId"));
        Long tenantId = EmsViewValueSupport.asLong(station.get("tenantId"));
        Long tabId = EmsRequestSupport.asLong(body.get("tabId"));
        EmsStationViewTab tab;
        if (tabId == null)
        {
            tab = new EmsStationViewTab();
            tab.setTenantId(tenantId);
            tab.setCompanyId(companyId);
            tab.setStationId(stationId);
            tab.setViewType(viewType);
            tab.setTabName(EmsRequestSupport.defaultString(body.get("tabName"), VIEW_TYPE_LOGIC.equals(viewType) ? "逻辑视图" : "物理视图"));
            tab.setSortNo(nextSortNo(stationId, viewType));
            tab.setStatus("0");
            stationViewTabMapper.insert(tab);
        }
        else
        {
            tab = requireTabAccess(tabId, viewType, stationId);
            if (body.containsKey("tabName"))
            {
                tab.setTabName(EmsRequestSupport.defaultString(body.get("tabName"), tab.getTabName()));
            }
            stationViewTabMapper.updateById(tab);
        }

        clearViewData(tab.getId());
        Map<String, Long> nodeKeyIdMap = saveViewNodes(tab, castList(body.get("nodes")));
        saveViewRelations(tab, castList(body.get("relations")), nodeKeyIdMap);
        saveOptimizerBindings(tab, castList(body.get("bindings")));

        Map<String, String> query = new LinkedHashMap<String, String>();
        query.put("stationId", String.valueOf(stationId));
        query.put("tabId", String.valueOf(tab.getId()));
        return VIEW_TYPE_LOGIC.equals(viewType) ? logicView(query) : stationView(query);
    }

    private Map<String, Long> saveViewNodes(EmsStationViewTab tab, List<Map<String, Object>> nodes)
    {
        Map<String, Long> keyIdMap = new HashMap<String, Long>();
        int sortNo = 1;
        for (Map<String, Object> item : nodes)
        {
            EmsStationViewNode node = new EmsStationViewNode();
            node.setTenantId(tab.getTenantId());
            node.setTabId(tab.getId());
            node.setNodeType(EmsRequestSupport.stringValue(item.get("nodeType")));
            node.setDeviceId(EmsRequestSupport.asLong(item.get("deviceId")));
            node.setComponentId(EmsRequestSupport.asLong(item.get("componentId")));
            node.setX(EmsRequestSupport.asBigDecimal(item.get("x")));
            node.setY(EmsRequestSupport.asBigDecimal(item.get("y")));
            node.setWidth(EmsRequestSupport.asBigDecimal(item.get("width")));
            node.setHeight(EmsRequestSupport.asBigDecimal(item.get("height")));
            node.setNodeStyleJson(EmsRequestSupport.stringValue(item.get("nodeStyleJson")));
            node.setSortNo(EmsRequestSupport.asInteger(item.get("sortNo"), sortNo));
            stationViewNodeMapper.insert(node);
            keyIdMap.put(resolveNodeKey(item, sortNo), node.getId());
            sortNo++;
        }
        return keyIdMap;
    }

    private void saveViewRelations(EmsStationViewTab tab, List<Map<String, Object>> relations, Map<String, Long> nodeKeyIdMap)
    {
        int sortNo = 1;
        for (Map<String, Object> item : relations)
        {
            Long sourceNodeId = resolveNodeReference(item.get("sourceNodeId"), item.get("sourceKey"), nodeKeyIdMap);
            Long targetNodeId = resolveNodeReference(item.get("targetNodeId"), item.get("targetKey"), nodeKeyIdMap);
            if (sourceNodeId == null || targetNodeId == null)
            {
                continue;
            }
            EmsStationViewRelation relation = new EmsStationViewRelation();
            relation.setTenantId(tab.getTenantId());
            relation.setTabId(tab.getId());
            relation.setSourceNodeId(sourceNodeId);
            relation.setTargetNodeId(targetNodeId);
            relation.setRelationType(EmsRequestSupport.defaultString(item.get("relationType"), "CONNECT"));
            relation.setLineStyleJson(EmsRequestSupport.stringValue(item.get("lineStyleJson")));
            relation.setSortNo(EmsRequestSupport.asInteger(item.get("sortNo"), sortNo));
            stationViewRelationMapper.insert(relation);
            sortNo++;
        }
    }

    private void saveOptimizerBindings(EmsStationViewTab tab, List<Map<String, Object>> bindings)
    {
        int sortNo = 1;
        for (Map<String, Object> item : bindings)
        {
            Long optimizerDeviceId = EmsRequestSupport.asLong(item.get("optimizerDeviceId"));
            if (optimizerDeviceId == null)
            {
                continue;
            }
            EmsOptimizerComponentBinding binding = new EmsOptimizerComponentBinding();
            binding.setTenantId(tab.getTenantId());
            binding.setTabId(tab.getId());
            binding.setOptimizerDeviceId(optimizerDeviceId);
            binding.setModuleDeviceId(EmsRequestSupport.asLong(item.get("moduleDeviceId")));
            binding.setComponentId(EmsRequestSupport.asLong(item.get("componentId")));
            binding.setBindingOrder(EmsRequestSupport.asInteger(item.get("bindingOrder"), sortNo));
            optimizerComponentBindingMapper.insert(binding);
            sortNo++;
        }
    }

    private void clearViewData(Long tabId)
    {
        stationViewNodeMapper.delete(new LambdaQueryWrapper<EmsStationViewNode>().eq(EmsStationViewNode::getTabId, tabId));
        stationViewRelationMapper.delete(new LambdaQueryWrapper<EmsStationViewRelation>().eq(EmsStationViewRelation::getTabId, tabId));
        optimizerComponentBindingMapper.delete(new LambdaQueryWrapper<EmsOptimizerComponentBinding>().eq(EmsOptimizerComponentBinding::getTabId, tabId));
    }

    private List<EmsStationViewTab> listTabs(Long stationId, String viewType)
    {
        Long tenantId = EmsViewValueSupport.asLong(requireStationAccess(stationId).get("tenantId"));
        return stationViewTabMapper.selectList(new LambdaQueryWrapper<EmsStationViewTab>()
                .eq(EmsStationViewTab::getTenantId, tenantId)
                .eq(EmsStationViewTab::getStationId, stationId)
                .eq(EmsStationViewTab::getViewType, viewType)
                .eq(EmsStationViewTab::getDelFlag, "0")
                .orderByAsc(EmsStationViewTab::getSortNo)
                .orderByAsc(EmsStationViewTab::getId));
    }

    private EmsStationViewTab resolveActiveTab(List<EmsStationViewTab> tabs, Long tabId)
    {
        if (tabId != null)
        {
            for (EmsStationViewTab tab : tabs)
            {
                if (tabId.equals(tab.getId()))
                {
                    return tab;
                }
            }
        }
        return tabs.isEmpty() ? null : tabs.get(0);
    }

    private List<Map<String, Object>> listViewNodes(Long tabId)
    {
        return stationViewNodeMapper.selectList(new LambdaQueryWrapper<EmsStationViewNode>()
                        .eq(EmsStationViewNode::getTabId, tabId)
                        .eq(EmsStationViewNode::getDelFlag, "0")
                        .orderByAsc(EmsStationViewNode::getSortNo)
                        .orderByAsc(EmsStationViewNode::getId))
                .stream()
                .map(this::toNodeMap)
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> listViewRelations(Long tabId)
    {
        return stationViewRelationMapper.selectList(new LambdaQueryWrapper<EmsStationViewRelation>()
                        .eq(EmsStationViewRelation::getTabId, tabId)
                        .eq(EmsStationViewRelation::getDelFlag, "0")
                        .orderByAsc(EmsStationViewRelation::getSortNo)
                        .orderByAsc(EmsStationViewRelation::getId))
                .stream()
                .map(this::toRelationMap)
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> listOptimizerBindings(Long tabId)
    {
        return optimizerComponentBindingMapper.selectList(new LambdaQueryWrapper<EmsOptimizerComponentBinding>()
                        .eq(EmsOptimizerComponentBinding::getTabId, tabId)
                        .eq(EmsOptimizerComponentBinding::getDelFlag, "0")
                        .orderByAsc(EmsOptimizerComponentBinding::getBindingOrder)
                        .orderByAsc(EmsOptimizerComponentBinding::getId))
                .stream()
                .map(this::toBindingMap)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toTabMap(EmsStationViewTab tab)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", tab.getId());
        row.put("companyId", tab.getCompanyId());
        row.put("stationId", tab.getStationId());
        row.put("tabName", tab.getTabName());
        row.put("viewType", tab.getViewType());
        row.put("sortNo", tab.getSortNo());
        row.put("status", tab.getStatus());
        row.put("remark", tab.getRemark());
        return row;
    }

    private Map<String, Object> toNodeMap(EmsStationViewNode node)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", node.getId());
        row.put("tabId", node.getTabId());
        row.put("nodeType", node.getNodeType());
        row.put("deviceId", node.getDeviceId());
        row.put("componentId", node.getComponentId());
        row.put("x", node.getX());
        row.put("y", node.getY());
        row.put("width", node.getWidth());
        row.put("height", node.getHeight());
        row.put("nodeStyleJson", node.getNodeStyleJson());
        row.put("sortNo", node.getSortNo());
        return row;
    }

    private Map<String, Object> toRelationMap(EmsStationViewRelation relation)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", relation.getId());
        row.put("tabId", relation.getTabId());
        row.put("sourceNodeId", relation.getSourceNodeId());
        row.put("targetNodeId", relation.getTargetNodeId());
        row.put("relationType", relation.getRelationType());
        row.put("lineStyleJson", relation.getLineStyleJson());
        row.put("sortNo", relation.getSortNo());
        return row;
    }

    private Map<String, Object> toBindingMap(EmsOptimizerComponentBinding binding)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", binding.getId());
        row.put("tabId", binding.getTabId());
        row.put("optimizerDeviceId", binding.getOptimizerDeviceId());
        row.put("moduleDeviceId", binding.getModuleDeviceId());
        row.put("componentId", binding.getComponentId());
        row.put("bindingOrder", binding.getBindingOrder());
        return row;
    }

    private EmsStationViewTab requireTabAccess(Long tabId, String expectedViewType, Long expectedStationId)
    {
        if (tabId == null)
        {
            throw new ServiceException("页签不存在");
        }
        EmsStationViewTab tab = stationViewTabMapper.selectById(tabId);
        if (tab == null || !"0".equals(tab.getDelFlag()))
        {
            throw new ServiceException("页签不存在");
        }
        if (expectedViewType != null && !expectedViewType.equalsIgnoreCase(tab.getViewType()))
        {
            throw new ServiceException("页签类型不匹配");
        }
        if (expectedStationId != null && !expectedStationId.equals(tab.getStationId()))
        {
            throw new ServiceException("页签不属于当前电站");
        }
        Map<String, Object> station = requireStationAccess(tab.getStationId());
        if (!java.util.Objects.equals(tab.getTenantId(), EmsViewValueSupport.asLong(station.get("tenantId"))))
        {
            throw new ServiceException("页签与电站不属于同一租户");
        }
        return tab;
    }

    private Map<String, Object> requireStationAccess(Long stationId)
    {
        if (stationId == null)
        {
            throw new ServiceException("电站不能为空");
        }
        Map<String, Object> station = readSupport.station(stationId);
        if (station == null || station.isEmpty())
        {
            throw new ServiceException("电站不存在或无权限");
        }
        return station;
    }

    private int nextSortNo(Long stationId, String viewType)
    {
        List<EmsStationViewTab> tabs = listTabs(stationId, viewType);
        int max = 0;
        for (EmsStationViewTab tab : tabs)
        {
            if (tab.getSortNo() != null && tab.getSortNo() > max)
            {
                max = tab.getSortNo();
            }
        }
        return max + 1;
    }

    private String normalizeViewType(String viewType)
    {
        String normalized = EmsRequestSupport.defaultString(viewType, VIEW_TYPE_PHYSICAL).toUpperCase();
        if (!VIEW_TYPE_PHYSICAL.equals(normalized) && !VIEW_TYPE_LOGIC.equals(normalized))
        {
            throw new ServiceException("视图类型不合法");
        }
        return normalized;
    }

    private Map<String, Object> castMap(Object value)
    {
        return value instanceof Map ? new LinkedHashMap<String, Object>((Map<String, Object>) value) : new LinkedHashMap<String, Object>();
    }

    private List<Map<String, Object>> castList(Object value)
    {
        if (!(value instanceof Collection))
        {
            return Collections.emptyList();
        }
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Object item : (Collection<?>) value)
        {
            if (item instanceof Map)
            {
                rows.add(new LinkedHashMap<String, Object>((Map<String, Object>) item));
            }
        }
        return rows;
    }

    private String resolveNodeKey(Map<String, Object> item, int index)
    {
        String key = EmsRequestSupport.stringValue(item.get("clientKey"));
        if (!key.isEmpty())
        {
            return key;
        }
        Long id = EmsRequestSupport.asLong(item.get("id"));
        if (id != null)
        {
            return String.valueOf(id);
        }
        return "node-" + index;
    }

    private Long resolveNodeReference(Object nodeIdValue, Object nodeKeyValue, Map<String, Long> nodeKeyIdMap)
    {
        Long nodeId = EmsRequestSupport.asLong(nodeIdValue);
        if (nodeId != null)
        {
            return nodeId;
        }
        String nodeKey = EmsRequestSupport.stringValue(nodeKeyValue);
        if (!nodeKey.isEmpty())
        {
            return nodeKeyIdMap.get(nodeKey);
        }
        return null;
    }

    private Map<String, Object> emptyStorageDetail()
    {
        Map<String, Object> empty = new LinkedHashMap<String, Object>();
        empty.put("summary", new LinkedHashMap<String, Object>());
        empty.put("detail", new LinkedHashMap<String, Object>());
        empty.put("tabs", Collections.emptyList());
        empty.put("realtimeMetrics", Collections.emptyList());
        empty.put("healthRows", Collections.emptyList());
        empty.put("identityRows", Collections.emptyList());
        empty.put("storageUnits", Collections.emptyList());
        empty.put("batteryPacks", Collections.emptyList());
        empty.put("statusRows", Collections.emptyList());
        empty.put("socTrend", emptyChart());
        empty.put("powerTrend", emptyChart());
        return empty;
    }

    private List<Map<String, Object>> buildOpsStationRows(List<Map<String, Object>> stations,
                                                          List<Map<String, Object>> devices,
                                                          List<Map<String, Object>> alarms,
                                                          Map<Long, BigDecimal> generationMap,
                                                          Map<Long, String> latestSampleTimes,
                                                          Map<Long, Map<String, Object>> syncStatusMap)
    {
        return new EmsOpsServiceImplBuilder().build(stations, devices, alarms, generationMap, latestSampleTimes, syncStatusMap);
    }

    private List<Map<String, Object>> buildAlarmRows(List<Map<String, Object>> alarms)
    {
        List<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
        for (Map<String, Object> alarm : alarms)
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("time", alarm.get("time"));
            row.put("stationName", alarm.get("stationName"));
            row.put("deviceName", alarm.get("deviceName"));
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

    private BigDecimal sumStationGeneration(List<Map<String, Object>> stations, Map<Long, BigDecimal> generationMap)
    {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> station : stations)
        {
            Long stationId = EmsViewValueSupport.asLong(station.get("stationId"));
            sum = sum.add(generationMap.containsKey(stationId) ? generationMap.get(stationId) : BigDecimal.ZERO);
        }
        return sum;
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

    private List<Map<String, Object>> enrichStationRevenue(List<Map<String, Object>> rows, Map<Long, BigDecimal> revenueMap,
                                                           Map<Long, Map<String, Object>> revenueQualityMap)
    {
        for (Map<String, Object> row : rows)
        {
            Long stationId = EmsViewValueSupport.asLong(row.get("stationId"));
            row.put("todayRevenue", revenueMap.containsKey(stationId) ? revenueMap.get(stationId) : BigDecimal.ZERO);
            enrichRevenueStatus(row, stationId, revenueMap, revenueQualityMap);
        }
        return rows;
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

    private static void enrichSyncStatus(Map<String, Object> row, Long stationId, Map<Long, Map<String, Object>> syncStatusMap)
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

    private Map<String, Object> detailRow(String label, Object value, String remark)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("label", label);
        row.put("value", value);
        row.put("remark", remark);
        return row;
    }

    private String metricValue(BigDecimal value, String unit)
    {
        return normalizeMetric(value).toPlainString() + " " + unit;
    }

    private String nullableMetricValue(BigDecimal value, String unit)
    {
        return value == null ? "--" : value.toPlainString() + " " + unit;
    }

    private String textValue(Object value)
    {
        if (value == null)
        {
            return "--";
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? "--" : text;
    }

    private Map<String, Object> emptyChart()
    {
        Map<String, Object> chart = new LinkedHashMap<String, Object>();
        chart.put("xAxisData", Collections.emptyList());
        chart.put("expectedData", Collections.emptyList());
        chart.put("actualData", Collections.emptyList());
        return chart;
    }

    private Map<String, Object> resolveSelectedStation(Map<String, String> query,
                                                       List<Map<String, Object>> stations,
                                                       Map<Long, BigDecimal> generationMap,
                                                       Map<Long, BigDecimal> revenueMap,
                                                       Map<Long, Map<String, Object>> revenueQualityMap,
                                                       Map<Long, Map<String, Object>> metricMap,
                                                       Map<Long, String> latestSampleTimes,
                                                       Map<Long, Map<String, Object>> syncStatusMap,
                                                       List<Map<String, Object>> alarms,
                                                       List<Map<String, Object>> devices)
    {
        Long targetStationId = EmsViewValueSupport.asLong(query == null ? null : query.get("stationId"));
        Map<String, Object> station = null;
        for (Map<String, Object> item : stations)
        {
            if (targetStationId != null && targetStationId.equals(EmsViewValueSupport.asLong(item.get("stationId"))))
            {
                station = item;
                break;
            }
        }
        if (station == null && !stations.isEmpty())
        {
            station = stations.get(0);
        }
        if (station == null)
        {
            return new LinkedHashMap<String, Object>();
        }

        Long stationId = EmsViewValueSupport.asLong(station.get("stationId"));
        Map<String, Object> detail = new LinkedHashMap<String, Object>(station);
        detail.put("todayGeneration", generationMap.containsKey(stationId) ? generationMap.get(stationId) : BigDecimal.ZERO);
        Map<String, Object> metrics = metricMap.get(stationId);
        detail.put("totalGeneration", metricValue(metrics, "totalGenerationKwh", station.get("totalGeneration")));
        detail.put("yearGeneration", metricValue(metrics, "yearGenerationKwh", station.get("yearGeneration")));
        detail.put("todayRevenue", revenueMap.containsKey(stationId) ? revenueMap.get(stationId) : BigDecimal.ZERO);
        enrichRevenueStatus(detail, stationId, revenueMap, revenueQualityMap);
        detail.put("latestDataTime", latestSampleTimes.containsKey(stationId) ? latestSampleTimes.get(stationId) : "");
        enrichSyncStatus(detail, stationId, syncStatusMap);
        detail.put("alarmCount", countStationAlarms(stationId, alarms));
        detail.put("deviceCount", countStationDevices(stationId, devices));
        detail.put("weather", "");
        detail.put("stringCapacityKw", station.get("capacityKw"));
        Long tenantId = EmsViewValueSupport.asLong(station.get("tenantId"));
        Long companyId = EmsViewValueSupport.asLong(station.get("companyId"));
        detail.put("coalFactor", businessParamResolver.resolveDecimal(EmsBusinessParamTemplate.STANDARD_COAL_FACTOR, tenantId, companyId, stationId));
        detail.put("co2Factor", businessParamResolver.resolveDecimal(EmsBusinessParamTemplate.CO2_FACTOR, tenantId, companyId, stationId));
        detail.put("treeFactor", businessParamResolver.resolveDecimal(EmsBusinessParamTemplate.TREE_FACTOR, tenantId, companyId, stationId));
        return detail;
    }

    private BigDecimal metricValue(Map<String, Object> metrics, String key, Object fallback)
    {
        if (metrics != null && metrics.containsKey(key))
        {
            return EmsViewValueSupport.asDecimal(metrics.get(key));
        }
        return EmsViewValueSupport.asDecimal(fallback);
    }

    private Map<String, Object> buildEnergyFlow(Map<String, Object> selectedStation, List<Map<String, Object>> devices)
    {
        Map<String, Object> flow = new LinkedHashMap<String, Object>();
        Long stationId = EmsViewValueSupport.asLong(selectedStation.get("stationId"));
        int pvCount = countStationDeviceType(stationId, devices, "INVERTER");
        int storageCount = countStationDeviceType(stationId, devices, "ESS");
        int meterCount = countStationDeviceType(stationId, devices, "METER");
        int loadCount = Math.max(0, countStationDevices(stationId, devices) - pvCount - storageCount - meterCount);
        BigDecimal pvPower = sumStationDevicePower(stationId, devices, "INVERTER");
        BigDecimal storagePower = sumStationDevicePower(stationId, devices, "ESS");
        BigDecimal meterPower = sumStationDevicePower(stationId, devices, "METER");
        BigDecimal loadPower = pvPower.add(storagePower).subtract(meterPower).max(BigDecimal.ZERO).setScale(2, BigDecimal.ROUND_HALF_UP);
        BigDecimal gridPower = meterPower.setScale(2, BigDecimal.ROUND_HALF_UP);

        List<Map<String, Object>> nodes = new java.util.ArrayList<Map<String, Object>>();
        nodes.add(flowNode("pv", "光伏", pvCount > 0, pvPower, pvCount));
        nodes.add(flowNode("storage", "储能", storageCount > 0, storagePower, storageCount));
        nodes.add(flowNode("load", "负载", loadCount > 0, loadPower, loadCount));
        nodes.add(flowNode("grid", "电网", meterCount > 0, gridPower.abs(), meterCount));
        flow.put("nodes", nodes);

        List<Map<String, Object>> edges = new java.util.ArrayList<Map<String, Object>>();
        if (pvCount > 0)
        {
            edges.add(flowEdge("pv", "load", pvPower, "OUT"));
        }
        if (storageCount > 0)
        {
            edges.add(flowEdge("storage", "load", storagePower.abs(), storagePower.signum() >= 0 ? "OUT" : "IN"));
        }
        if (meterCount > 0)
        {
            edges.add(flowEdge(gridPower.signum() >= 0 ? "grid" : "load", gridPower.signum() >= 0 ? "load" : "grid", gridPower.abs(), gridPower.signum() >= 0 ? "OUT" : "IN"));
        }
        flow.put("edges", edges);
        flow.put("visible", availableNodeCount(nodes) >= 3);
        return flow;
    }

    private Map<String, Object> buildSessionControl(Map<String, Object> selectedStation)
    {
        Map<String, Object> session = new LinkedHashMap<String, Object>();
        Long companyId = EmsViewValueSupport.asLong(selectedStation.get("companyId"));
        Long stationId = EmsViewValueSupport.asLong(selectedStation.get("stationId"));
        int monthlyLimit = 300;
        int sessionSeconds = 60;
        int usedCount = countCurrentMonthSessions(stationId);
        session.put("monthlyLimit", monthlyLimit);
        session.put("sessionSeconds", sessionSeconds);
        session.put("usedCount", usedCount);
        session.put("remainingCount", Math.max(0, monthlyLimit - usedCount));
        return session;
    }

    private List<Map<String, Object>> buildStationDevices(Map<String, Object> selectedStation, List<Map<String, Object>> devices)
    {
        Long stationId = EmsViewValueSupport.asLong(selectedStation.get("stationId"));
        if (stationId == null)
        {
            return Collections.emptyList();
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
        for (Map<String, Object> device : devices)
        {
            if (!stationId.equals(EmsViewValueSupport.asLong(device.get("stationId"))))
            {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>(device);
            row.put("activePower", devicePowerValue(device));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> buildRealtimeMetrics(Map<String, Object> selectedStation, List<Map<String, Object>> devices)
    {
        Long stationId = EmsViewValueSupport.asLong(selectedStation.get("stationId"));
        List<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
        rows.add(metricRow("逆变器数量", countStationDeviceType(stationId, devices, "INVERTER"), "台"));
        rows.add(metricRow("储能数量", countStationDeviceType(stationId, devices, "ESS"), "套"));
        rows.add(metricRow("电表接入", countStationDeviceType(stationId, devices, "METER") > 0 ? "是" : "否", ""));
        rows.add(metricRow("最新采样", selectedStation.get("latestDataTime"), ""));
        return rows;
    }

    private Map<String, Object> flowNode(String key, String label, boolean available, BigDecimal power, int deviceCount)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("key", key);
        row.put("label", label);
        row.put("available", available);
        row.put("power", power == null ? BigDecimal.ZERO : power);
        row.put("deviceCount", deviceCount);
        return row;
    }

    private Map<String, Object> flowEdge(String from, String to, BigDecimal power, String direction)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("from", from);
        row.put("to", to);
        row.put("power", power == null ? BigDecimal.ZERO : power);
        row.put("direction", direction);
        return row;
    }

    private int availableNodeCount(List<Map<String, Object>> nodes)
    {
        int count = 0;
        for (Map<String, Object> node : nodes)
        {
            if (Boolean.TRUE.equals(node.get("available")))
            {
                count++;
            }
        }
        return count;
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

    private int countStationDeviceType(Long stationId, List<Map<String, Object>> devices, String deviceType)
    {
        int count = 0;
        for (Map<String, Object> device : devices)
        {
            if (stationId != null
                    && stationId.equals(EmsViewValueSupport.asLong(device.get("stationId")))
                    && deviceType.equalsIgnoreCase(String.valueOf(device.get("deviceType"))))
            {
                count++;
            }
        }
        return count;
    }

    private BigDecimal defaultDecimal(Object value)
    {
        if (value instanceof BigDecimal)
        {
            return (BigDecimal) value;
        }
        if (value == null)
        {
            return BigDecimal.ZERO;
        }
        try
        {
            return new BigDecimal(String.valueOf(value));
        }
        catch (Exception ex)
        {
            return BigDecimal.ZERO;
        }
    }

    private Object devicePowerValue(Map<String, Object> device)
    {
        Long deviceId = EmsViewValueSupport.asLong(device.get("deviceId"));
        if (deviceId == null)
        {
            return BigDecimal.ZERO;
        }
        BigDecimal power = readSupport.deviceMetricValue(deviceId, "activePower");
        if (power == null)
        {
            power = BigDecimal.ZERO;
        }
        return power.setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    private BigDecimal sumStationDevicePower(Long stationId, List<Map<String, Object>> devices, String deviceType)
    {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> device : devices)
        {
            if (stationId == null
                    || !stationId.equals(EmsViewValueSupport.asLong(device.get("stationId")))
                    || !deviceType.equalsIgnoreCase(String.valueOf(device.get("deviceType"))))
            {
                continue;
            }
            Object powerValue = devicePowerValue(device);
            total = total.add(defaultDecimal(powerValue));
        }
        return total.setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    private Map<String, Object> metricRow(String name, Object value, String unit)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("name", name);
        row.put("value", value);
        row.put("unit", unit);
        return row;
    }

    private Map<String, Object> tab(String key, String label)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("key", key);
        row.put("label", label);
        return row;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildHistoryRows(Map<String, Object> trend, String metricKey, Map<String, Object> device)
    {
        List<Object> xAxisData = trend == null ? Collections.emptyList() : (List<Object>) trend.get("xAxisData");
        List<Object> values = trend == null ? Collections.emptyList() : (List<Object>) trend.get("actualData");
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < xAxisData.size() && index < values.size(); index++)
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("stationName", device.get("stationName"));
            row.put("deviceName", device.get("deviceName"));
            row.put("sampleTime", xAxisData.get(index));
            row.put("time", xAxisData.get(index));
            row.put("metricKey", metricKey);
            row.put("metricValue", values.get(index));
            row.put("value", values.get(index));
            row.put("unit", metricUnit(metricKey));
            row.put("dataQuality", "GOOD");
            row.put("quality", "GOOD");
            rows.add(row);
        }
        return rows;
    }

    private BigDecimal maxValue(List<Object> values, BigDecimal fallback)
    {
        if (values == null || values.isEmpty())
        {
            return fallback == null ? BigDecimal.ZERO : fallback;
        }
        BigDecimal max = null;
        for (Object value : values)
        {
            BigDecimal decimal = defaultDecimal(value);
            max = max == null ? decimal : max.max(decimal);
        }
        return max == null ? BigDecimal.ZERO : max.setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    private BigDecimal minValue(List<Object> values, BigDecimal fallback)
    {
        if (values == null || values.isEmpty())
        {
            return fallback == null ? BigDecimal.ZERO : fallback;
        }
        BigDecimal min = null;
        for (Object value : values)
        {
            BigDecimal decimal = defaultDecimal(value);
            min = min == null ? decimal : min.min(decimal);
        }
        return min == null ? BigDecimal.ZERO : min.setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    private String historyMetricKey(String deviceType)
    {
        return "activePower";
    }

    private String metricUnit(String metricKey)
    {
        if (metricKey == null)
        {
            return "";
        }
        if ("soc".equalsIgnoreCase(metricKey) || "soh".equalsIgnoreCase(metricKey))
        {
            return "%";
        }
        if (metricKey.toLowerCase().contains("kwh") || metricKey.toLowerCase().contains("energy"))
        {
            return "kWh";
        }
        if (metricKey.toLowerCase().contains("power"))
        {
            return "kW";
        }
        return "";
    }

    private BigDecimal normalizeMetric(BigDecimal value)
    {
        return value == null ? BigDecimal.ZERO : value.setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    private BigDecimal normalizeNullableMetric(BigDecimal value)
    {
        return value == null ? null : value.setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    private int countCurrentMonthSessions(Long stationId)
    {
        if (stationId == null)
        {
            return 0;
        }
        Date monthStart = startOfMonth();
        Date monthEnd = endOfMonth();
        Long tenantId = EmsViewValueSupport.asLong(requireStationAccess(stationId).get("tenantId"));
        Long count = energyFlowSessionMapper.selectCount(new LambdaQueryWrapper<EmsEnergyFlowSession>()
                .eq(EmsEnergyFlowSession::getTenantId, tenantId)
                .eq(EmsEnergyFlowSession::getStationId, stationId)
                .ge(EmsEnergyFlowSession::getStartedAt, monthStart)
                .le(EmsEnergyFlowSession::getStartedAt, monthEnd)
                .eq(EmsEnergyFlowSession::getDelFlag, "0"));
        return count == null ? 0 : count.intValue();
    }

    private Date startOfMonth()
    {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    private Date endOfMonth()
    {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH));
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTime();
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

    private static class EmsOpsServiceImplBuilder
    {
        List<Map<String, Object>> build(List<Map<String, Object>> stations,
                                        List<Map<String, Object>> devices,
                                        List<Map<String, Object>> alarms,
                                        Map<Long, BigDecimal> generationMap,
                                        Map<Long, String> latestSampleTimes,
                                        Map<Long, Map<String, Object>> syncStatusMap)
        {
            List<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
            for (Map<String, Object> station : stations)
            {
                Long stationId = EmsViewValueSupport.asLong(station.get("stationId"));
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("stationId", stationId);
                row.put("stationName", station.get("stationName"));
                row.put("status", stationStatus(stationId, devices, alarms));
                row.put("latestDataTime", latestSampleTimes.containsKey(stationId) ? latestSampleTimes.get(stationId) : "");
                enrichSyncStatus(row, stationId, syncStatusMap);
                row.put("deviceCount", countStationDevices(stationId, devices));
                row.put("alarmCount", countStationAlarms(stationId, alarms));
                row.put("todayGeneration", generationMap.containsKey(stationId) ? generationMap.get(stationId) : BigDecimal.ZERO);
                row.put("commStatus", stationCommStatus(stationId, devices));
                rows.add(row);
            }
            return rows;
        }

        private String stationStatus(Long stationId, List<Map<String, Object>> devices, List<Map<String, Object>> alarms)
        {
            if (countStationAlarms(stationId, alarms) > 0)
            {
                return "ALARM";
            }
            for (Map<String, Object> device : devices)
            {
                if (stationId != null && stationId.equals(EmsViewValueSupport.asLong(device.get("stationId")))
                        && !"ONLINE".equalsIgnoreCase(String.valueOf(device.get("commStatus"))))
                {
                    return "WARNING";
                }
            }
            return "ONLINE";
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
    }
}
