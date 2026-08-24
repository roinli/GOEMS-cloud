package com.witos.ems.server.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.domain.entity.EmsOpenemsDevice;
import com.witos.ems.server.domain.entity.EmsOpenemsCapability;
import com.witos.ems.server.domain.entity.EmsOpenemsEndpointSource;
import com.witos.ems.server.domain.entity.EmsStation;
import com.witos.ems.server.mapper.EmsOpenemsCapabilityMapper;
import com.witos.ems.server.mapper.EmsOpenemsDeviceMapper;
import com.witos.ems.server.mapper.EmsOpenemsEndpointSourceMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.ems.server.openems.OpenemsInfluxQueryClient;
import com.witos.ems.server.openems.OpenemsInfluxQueryClient.Sample;
import com.witos.ems.server.service.EmsOpenemsTimeseriesService;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EmsOpenemsTimeseriesServiceImpl implements EmsOpenemsTimeseriesService
{
    private static final Pattern EDGE_NUMBER = Pattern.compile("\\D++(\\d++)$");
    private static final Pattern CHANNEL = Pattern.compile("^[A-Za-z0-9_.:-]+/[A-Za-z0-9_.:-]+$");
    private static final int STALE_SECONDS = 600;
    private static final int MAX_CHANNELS = 64;
    private static final int MAX_POINTS = 10000;
    private static final long MAX_RANGE_SECONDS = 366L * 24L * 60L * 60L;

    @Resource
    private EmsOpenemsDeviceMapper deviceMapper;

    @Resource
    private EmsOpenemsCapabilityMapper capabilityMapper;

    @Resource
    private EmsOpenemsEndpointSourceMapper sourceMapper;

    @Resource
    private EmsStationMapper stationMapper;

    @Resource
    private OpenemsInfluxQueryClient queryClient;

    @Override
    public Map<String, Object> latest(Long deviceId, Map<String, String> query)
    {
        QueryContext context = context(deviceId, query);
        if ("DISABLED".equals(context.device.getStatus()))
        {
            throw new ServiceException("设备已停用，不再刷新实时监控数据");
        }
        if (context.edgeKey == null)
        {
            return unavailable(context.device, "TIMESERIES_UNAVAILABLE_EDGE_ID_FORMAT");
        }
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        Date latestTime = null;
        String overallQuality = "GOOD";
        for (String channel : context.channels)
        {
            Sample sample = queryClient.queryLatest(context.raw, context.edgeKey, channel);
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("channel", channel);
            row.put("source", "RAW");
            if (sample == null)
            {
                row.put("value", null);
                row.put("sampleTime", null);
                row.put("quality", "MISSING");
                overallQuality = "MISSING";
            }
            else
            {
                String quality = sample.getSampleTime().before(new Date(System.currentTimeMillis() - STALE_SECONDS * 1000L))
                        ? "STALE" : "GOOD";
                row.put("value", sample.getValue());
                row.put("sampleTime", sample.getSampleTime());
                row.put("quality", quality);
                if (latestTime == null || latestTime.before(sample.getSampleTime()))
                {
                    latestTime = sample.getSampleTime();
                }
                if ("STALE".equals(quality) && "GOOD".equals(overallQuality))
                {
                    overallQuality = "STALE";
                }
            }
            values.add(row);
        }
        Map<String, Object> result = baseResult(context);
        result.put("available", true);
        result.put("source", "RAW");
        result.put("sampleTime", latestTime);
        result.put("quality", overallQuality);
        result.put("values", values);
        return result;
    }

    @Override
    public Map<String, Object> history(Long deviceId, Map<String, String> query)
    {
        QueryContext context = context(deviceId, query);
        if (context.edgeKey == null)
        {
            return unavailable(context.device, "TIMESERIES_UNAVAILABLE_EDGE_ID_FORMAT");
        }
        Instant from = parseTime(required(query, "from", "历史开始时间不能为空"), context.zoneId);
        Instant to = parseTime(required(query, "to", "历史结束时间不能为空"), context.zoneId);
        long rangeSeconds = Duration.between(from, to).getSeconds();
        if (rangeSeconds <= 0)
        {
            throw new ServiceException("历史结束时间必须晚于开始时间");
        }
        if (rangeSeconds > MAX_RANGE_SECONDS)
        {
            throw new ServiceException("单次历史查询不能超过366天");
        }
        int intervalSeconds = interval(query.get("intervalSeconds"), rangeSeconds);
        String aggregation = StringUtils.isEmpty(query.get("aggregation")) ? "MEAN" : query.get("aggregation").toUpperCase();
        int offsetSeconds = context.zoneId.getRules().getOffset(from).getTotalSeconds();

        HistoryQueryResult selected = queryAggregated(context, from, to, intervalSeconds, aggregation, offsetSeconds);
        if (!selected.covered)
        {
            String fallbackReason = selected.fallbackReason;
            selected = querySource(context.raw, context.edgeKey, context.channels, from, to,
                    intervalSeconds, aggregation, offsetSeconds, context.raw.getMeasurement(), context.raw.getRetentionPolicy());
            selected.source = "RAW";
            selected.fallbackReason = fallbackReason;
        }
        Map<String, Object> result = baseResult(context);
        result.put("available", true);
        result.put("source", selected.source);
        result.put("quality", selected.quality);
        result.put("fallbackReason", selected.fallbackReason);
        result.put("from", Date.from(from));
        result.put("to", Date.from(to));
        result.put("intervalSeconds", intervalSeconds);
        result.put("aggregation", aggregation);
        result.put("rows", toRows(selected.rows));
        return result;
    }

    private HistoryQueryResult queryAggregated(QueryContext context, Instant from, Instant to, int intervalSeconds,
                                                String aggregation, int offsetSeconds)
    {
        if (context.aggregated == null || !"0".equals(context.aggregated.getEnabled()))
        {
            return HistoryQueryResult.fallback("AGGREGATED_NOT_CONFIGURED");
        }
        if (!"MEAN".equals(aggregation) && !"LAST".equals(aggregation))
        {
            return HistoryQueryResult.fallback("AGGREGATED_AGGREGATION_UNSUPPORTED");
        }
        JSONObject config;
        try
        {
            config = StringUtils.isEmpty(context.aggregated.getQueryConfigJson())
                    ? null : JSON.parseObject(context.aggregated.getQueryConfigJson());
        }
        catch (RuntimeException ex)
        {
            return HistoryQueryResult.fallback("AGGREGATED_QUERY_CONFIG_INVALID");
        }
        JSONObject average = config == null ? null : config.getJSONObject("average");
        JSONObject cumulative = config == null ? null : config.getJSONObject("cumulative");
        if (average == null && cumulative == null)
        {
            return HistoryQueryResult.fallback("AGGREGATED_QUERY_CONFIG_MISSING");
        }
        Set<String> averageChannels = average == null ? Collections.emptySet() : stringSet(average.getJSONArray("channels"));
        Set<String> cumulativeChannels = cumulative == null ? Collections.emptySet() : stringSet(cumulative.getJSONArray("channels"));
        boolean requestsAverage = false;
        boolean requestsCumulative = false;
        for (String channel : context.channels)
        {
            if (!averageChannels.contains(channel) && !cumulativeChannels.contains(channel))
            {
                return HistoryQueryResult.fallback("AGGREGATED_CHANNEL_NOT_COVERED");
            }
            requestsAverage |= averageChannels.contains(channel);
            requestsCumulative |= cumulativeChannels.contains(channel);
        }
        String averageMeasurement = average == null ? null : average.getString("measurement");
        if (StringUtils.isEmpty(averageMeasurement) && average != null)
        {
            averageMeasurement = context.aggregated.getMeasurement();
        }
        Set<String> averageTimezones = average == null ? Collections.emptySet() : stringSet(average.getJSONArray("timezones"));
        if (requestsAverage
                && ((!averageTimezones.isEmpty() && !averageTimezones.contains(context.zoneId.getId()))
                || (averageTimezones.isEmpty() && !context.zoneId.getId().equals(context.aggregated.getTimezone()))))
        {
            return HistoryQueryResult.fallback("AGGREGATED_TIMEZONE_NOT_COVERED");
        }
        String cumulativeMeasurement = null;
        if (requestsCumulative)
        {
            JSONObject measurementByTimezone = cumulative == null ? null : cumulative.getJSONObject("measurementByTimezone");
            cumulativeMeasurement = measurementByTimezone == null ? null : measurementByTimezone.getString(context.zoneId.getId());
            if (StringUtils.isEmpty(cumulativeMeasurement))
            {
                return HistoryQueryResult.fallback("AGGREGATED_TIMEZONE_NOT_COVERED");
            }
        }
        if ((requestsAverage && StringUtils.isEmpty(averageMeasurement))
                || (requestsCumulative && StringUtils.isEmpty(cumulativeMeasurement)))
        {
            return HistoryQueryResult.fallback("AGGREGATED_MEASUREMENT_MISSING");
        }
        try
        {
            TreeMap<Date, Map<String, Object>> rows = new TreeMap<Date, Map<String, Object>>();
            int expected = (int) ((Duration.between(from, to).getSeconds() + intervalSeconds - 1) / intervalSeconds);
            int minimumPoints = Integer.MAX_VALUE;
            for (String channel : context.channels)
            {
                boolean isAverage = averageChannels.contains(channel);
                JSONObject channelConfig = isAverage ? average : cumulative;
                String measurement = isAverage ? averageMeasurement : cumulativeMeasurement;
                String retentionPolicy = channelConfig == null ? null : channelConfig.getString("retentionPolicy");
                List<Sample> samples = queryClient.queryHistory(context.aggregated, context.edgeKey, channel,
                        measurement, retentionPolicy, from, to, intervalSeconds, aggregation, offsetSeconds);
                minimumPoints = Math.min(minimumPoints, samples.size());
                for (Sample sample : samples)
                {
                    rows.computeIfAbsent(sample.getSampleTime(), ignored -> new LinkedHashMap<String, Object>())
                            .put(channel, sample.getValue());
                }
            }
            if (minimumPoints == Integer.MAX_VALUE || minimumPoints < expected)
            {
                return HistoryQueryResult.fallback("AGGREGATED_RANGE_NOT_COVERED");
            }
            HistoryQueryResult result = new HistoryQueryResult();
            result.rows = rows;
            result.minimumChannelPoints = minimumPoints;
            result.covered = true;
            result.source = "AGGREGATED";
            result.quality = "GOOD";
            return result;
        }
        catch (RuntimeException ex)
        {
            return HistoryQueryResult.fallback("AGGREGATED_QUERY_FAILED");
        }
    }

    private HistoryQueryResult querySource(EmsOpenemsEndpointSource source, String edgeKey, List<String> channels,
                                           Instant from, Instant to, int intervalSeconds, String aggregation,
                                           int offsetSeconds, String measurement, String retentionPolicy)
    {
        TreeMap<Date, Map<String, Object>> rows = new TreeMap<Date, Map<String, Object>>();
        int minimumPoints = Integer.MAX_VALUE;
        for (String channel : channels)
        {
            List<Sample> samples = queryClient.queryHistory(source, edgeKey, channel, measurement, retentionPolicy,
                    from, to, intervalSeconds, aggregation, offsetSeconds);
            minimumPoints = Math.min(minimumPoints, samples.size());
            for (Sample sample : samples)
            {
                rows.computeIfAbsent(sample.getSampleTime(), ignored -> new LinkedHashMap<String, Object>())
                        .put(channel, sample.getValue());
            }
        }
        if (minimumPoints == Integer.MAX_VALUE)
        {
            minimumPoints = 0;
        }
        HistoryQueryResult result = new HistoryQueryResult();
        result.rows = rows;
        result.minimumChannelPoints = minimumPoints;
        result.covered = minimumPoints > 0;
        int expected = (int) ((Duration.between(from, to).getSeconds() + intervalSeconds - 1) / intervalSeconds);
        result.quality = minimumPoints == 0 ? "MISSING"
                : minimumPoints < expected || "LAST".equals(aggregation) ? "PARTIAL" : "GOOD";
        return result;
    }

    private QueryContext context(Long deviceId, Map<String, String> query)
    {
        if (query == null)
        {
            query = Collections.emptyMap();
        }
        EmsOpenemsDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsDevice>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsDevice::getTenantId,
                        EmsRequestSupport.currentTenantId())
                .eq(EmsOpenemsDevice::getId, deviceId)
                .eq(EmsOpenemsDevice::getDelFlag, "0")
                .last("limit 1"));
        if (device == null)
        {
            throw new ServiceException("OpenEMS设备不存在或不属于当前租户");
        }
        Long tenantId = device.getTenantId();
        Map<String, EmsOpenemsEndpointSource> sources = new LinkedHashMap<String, EmsOpenemsEndpointSource>();
        List<EmsOpenemsEndpointSource> sourceList = sourceMapper.selectList(new LambdaQueryWrapper<EmsOpenemsEndpointSource>()
                .eq(EmsOpenemsEndpointSource::getTenantId, tenantId)
                .eq(EmsOpenemsEndpointSource::getEndpointId, device.getEndpointId())
                .eq(EmsOpenemsEndpointSource::getDelFlag, "0"));
        if (sourceList != null)
        {
            for (EmsOpenemsEndpointSource source : sourceList)
            {
                sources.put(source.getSourceType(), source);
            }
        }
        EmsOpenemsEndpointSource raw = sources.get("RAW_INFLUX");
        if (raw == null || !"0".equals(raw.getEnabled()))
        {
            throw new ServiceException("Raw Influx未配置或未启用");
        }
        ZoneId zoneId = resolveTimezone(device, sources.get("API"), tenantId);
        QueryContext context = new QueryContext();
        context.device = device;
        context.raw = raw;
        context.aggregated = sources.get("AGGREGATED_INFLUX");
        context.zoneId = zoneId;
        context.edgeKey = edgeKey(device.getEdgeId());
        context.channels = channels(device, query.get("channels"));
        return context;
    }

    private ZoneId resolveTimezone(EmsOpenemsDevice device, EmsOpenemsEndpointSource api, Long tenantId)
    {
        String timezone;
        if (device.getStationId() != null)
        {
            EmsStation station = stationMapper.selectOne(new LambdaQueryWrapper<EmsStation>()
                    .eq(EmsStation::getTenantId, tenantId)
                    .eq(EmsStation::getId, device.getStationId())
                    .eq(EmsStation::getDelFlag, "0")
                    .last("limit 1"));
            if (station == null || StringUtils.isEmpty(station.getTimezone()))
            {
                throw new ServiceException("绑定电站不存在或未配置IANA时区");
            }
            timezone = station.getTimezone();
        }
        else
        {
            if (api == null || StringUtils.isEmpty(api.getTimezone()))
            {
                throw new ServiceException("未绑定设备所属端点未配置默认IANA时区");
            }
            timezone = api.getTimezone();
        }
        try
        {
            return ZoneId.of(timezone);
        }
        catch (RuntimeException ex)
        {
            throw new ServiceException("IANA时区配置无效：" + timezone);
        }
    }

    private List<String> channels(EmsOpenemsDevice device, String value)
    {
        if (StringUtils.isEmpty(value))
        {
            value = String.join(",", defaultChannels(device));
            if (StringUtils.isEmpty(value))
            {
                throw new ServiceException("设备未同步到可用历史通道，请先在线刷新Edge能力");
            }
        }
        if (StringUtils.isEmpty(device.getPrimaryComponentId()))
        {
            throw new ServiceException("设备未配置主Component，无法查询时序");
        }
        Set<String> result = new HashSet<String>();
        for (String item : value.split(","))
        {
            String channel = item.trim();
            if (!channel.contains("/"))
            {
                channel = device.getPrimaryComponentId() + "/" + channel;
            }
            if (!CHANNEL.matcher(channel).matches())
            {
                throw new ServiceException("OpenEMS通道格式无效：" + channel);
            }
            if (!channel.startsWith(device.getPrimaryComponentId() + "/"))
            {
                throw new ServiceException("初版只允许查询设备主Component通道：" + channel);
            }
            result.add(channel);
        }
        if (result.isEmpty() || result.size() > MAX_CHANNELS)
        {
            throw new ServiceException("单次查询通道数量需为1-" + MAX_CHANNELS);
        }
        List<String> sorted = new ArrayList<String>(result);
        Collections.sort(sorted);
        return sorted;
    }

    /**
     * Keep realtime, history and backfill on the same channel contract. The
     * simulator uses the normal OpenEMS component channels, so no simulator-
     * specific Influx field is needed here.
     */
    private List<String> defaultChannels(EmsOpenemsDevice device)
    {
        String type = StringUtils.isEmpty(device.getDeviceType()) ? "OTHER" : device.getDeviceType().toUpperCase();
        List<String> candidates;
        if ("ESS".equals(type) || "PCS".equals(type))
        {
            candidates = Arrays.asList("ActivePower", "ActiveChargeEnergy", "ActiveDischargeEnergy", "Soc");
        }
        else if ("INVERTER".equals(type))
        {
            candidates = Arrays.asList("ActivePower", "ActiveProductionEnergy");
        }
        else if ("CHARGER".equals(type))
        {
            candidates = Arrays.asList("ActivePower", "EnergySession", "ActiveConsumptionEnergy");
        }
        else if ("METER".equals(type))
        {
            candidates = Arrays.asList("ActivePower", "ActiveConsumptionEnergy", "ActiveProductionEnergy");
        }
        else
        {
            candidates = Collections.singletonList("ActivePower");
        }
        if (capabilityMapper == null || StringUtils.isEmpty(device.getPrimaryComponentId()))
        {
            return prefixChannels(device, candidates);
        }
        List<EmsOpenemsCapability> capabilities = capabilityMapper.selectList(new LambdaQueryWrapper<EmsOpenemsCapability>()
                .eq(EmsOpenemsCapability::getTenantId, device.getTenantId())
                .eq(EmsOpenemsCapability::getEndpointId, device.getEndpointId())
                .eq(EmsOpenemsCapability::getEdgeId, device.getEdgeId())
                .eq(EmsOpenemsCapability::getComponentId, device.getPrimaryComponentId())
                .eq(EmsOpenemsCapability::getStatus, "ACTIVE")
                .eq(EmsOpenemsCapability::getDelFlag, "0"));
        Set<String> available = new HashSet<String>();
        for (EmsOpenemsCapability capability : capabilities)
        {
            if (capability.getCapabilityKey() == null || !capability.getCapabilityKey().startsWith("channel:"))
            {
                continue;
            }
            String schema = capability.getChannelSchema();
            if (StringUtils.isEmpty(schema))
            {
                continue;
            }
            String id = JSON.parseObject(schema).getString("id");
            if (!StringUtils.isEmpty(id))
            {
                available.add(id);
            }
        }
        List<String> result = new ArrayList<String>();
        for (String candidate : candidates)
        {
            if (available.contains(candidate))
            {
                result.add(device.getPrimaryComponentId() + "/" + candidate);
            }
        }
        return result;
    }

    private List<String> prefixChannels(EmsOpenemsDevice device, List<String> channels)
    {
        List<String> result = new ArrayList<String>();
        for (String channel : channels)
        {
            result.add(device.getPrimaryComponentId() + "/" + channel);
        }
        return result;
    }

    private String edgeKey(String edgeId)
    {
        Matcher matcher = EDGE_NUMBER.matcher(edgeId == null ? "" : edgeId);
        if (!matcher.find())
        {
            return null;
        }
        BigInteger value = new BigInteger(matcher.group(1));
        if (value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0)
        {
            return null;
        }
        return value.toString();
    }

    private Instant parseTime(String value, ZoneId zoneId)
    {
        try
        {
            if (value.endsWith("Z"))
            {
                return Instant.parse(value);
            }
            if (value.matches(".*[+-]\\d{2}:\\d{2}$"))
            {
                return OffsetDateTime.parse(value).toInstant();
            }
            if (value.matches("\\d{4}-\\d{2}-\\d{2}"))
            {
                return LocalDate.parse(value).atStartOfDay(zoneId).toInstant();
            }
            return LocalDateTime.parse(value.replace(' ', 'T')).atZone(zoneId).toInstant();
        }
        catch (DateTimeParseException ex)
        {
            throw new ServiceException("时间格式无效，请使用ISO-8601或yyyy-MM-dd HH:mm:ss");
        }
    }

    private int interval(String value, long rangeSeconds)
    {
        int interval;
        try
        {
            interval = StringUtils.isEmpty(value) ? (int) Math.max(1L, (rangeSeconds + 1999L) / 2000L) : Integer.parseInt(value);
        }
        catch (NumberFormatException ex)
        {
            throw new ServiceException("intervalSeconds必须是正整数");
        }
        if (interval < 1 || interval > 86400)
        {
            throw new ServiceException("intervalSeconds需为1-86400秒");
        }
        if ((rangeSeconds + interval - 1) / interval > MAX_POINTS)
        {
            throw new ServiceException("单次查询点数不能超过" + MAX_POINTS + "，请增大intervalSeconds");
        }
        return interval;
    }

    private List<Map<String, Object>> toRows(TreeMap<Date, Map<String, Object>> source)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map.Entry<Date, Map<String, Object>> entry : source.entrySet())
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("sampleTime", entry.getKey());
            row.put("values", entry.getValue());
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> baseResult(QueryContext context)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("deviceId", context.device.getId());
        result.put("endpointId", context.device.getEndpointId());
        result.put("edgeId", context.device.getEdgeId());
        result.put("primaryComponentId", context.device.getPrimaryComponentId());
        result.put("timezone", context.zoneId.getId());
        return result;
    }

    private Map<String, Object> unavailable(EmsOpenemsDevice device, String reason)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("deviceId", device.getId());
        result.put("endpointId", device.getEndpointId());
        result.put("edgeId", device.getEdgeId());
        result.put("available", false);
        result.put("quality", "UNAVAILABLE");
        result.put("reason", reason);
        result.put("rows", Collections.emptyList());
        return result;
    }

    private Set<String> stringSet(JSONArray array)
    {
        Set<String> result = new HashSet<String>();
        if (array != null)
        {
            for (Object value : array)
            {
                if (value != null)
                {
                    result.add(String.valueOf(value));
                }
            }
        }
        return result;
    }

    private String required(Map<String, String> query, String key, String message)
    {
        String value = query == null ? null : query.get(key);
        if (StringUtils.isEmpty(value))
        {
            throw new ServiceException(message);
        }
        return value;
    }

    private static class QueryContext
    {
        private EmsOpenemsDevice device;
        private EmsOpenemsEndpointSource raw;
        private EmsOpenemsEndpointSource aggregated;
        private ZoneId zoneId;
        private String edgeKey;
        private List<String> channels;
    }

    private static class HistoryQueryResult
    {
        private TreeMap<Date, Map<String, Object>> rows = new TreeMap<Date, Map<String, Object>>();
        private int minimumChannelPoints;
        private boolean covered;
        private String source;
        private String quality;
        private String fallbackReason;

        private static HistoryQueryResult fallback(String reason)
        {
            HistoryQueryResult result = new HistoryQueryResult();
            result.covered = false;
            result.fallbackReason = reason;
            return result;
        }
    }
}
