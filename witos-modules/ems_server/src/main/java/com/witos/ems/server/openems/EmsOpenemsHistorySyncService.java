package com.witos.ems.server.openems;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.ems.server.domain.entity.EmsChannelMapping;
import com.witos.ems.server.domain.entity.EmsDeviceComponent;
import com.witos.ems.server.domain.entity.EmsMetricHistory5Min;
import com.witos.ems.server.domain.entity.EmsMetricSyncCursor;
import com.witos.ems.server.domain.entity.EmsOpenemsEndpointSource;
import com.witos.ems.server.domain.entity.EmsServerEndpoint;
import com.witos.ems.server.config.EmsMetricProperties;
import com.witos.ems.server.mapper.EmsChannelMappingMapper;
import com.witos.ems.server.mapper.EmsDeviceComponentMapper;
import com.witos.ems.server.mapper.EmsMetricHistory5MinMapper;
import com.witos.ems.server.mapper.EmsMetricSyncCursorMapper;
import com.witos.ems.server.mapper.EmsOpenemsEndpointSourceMapper;
import com.witos.ems.server.mapper.EmsServerEndpointMapper;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.time.Instant;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EmsOpenemsHistorySyncService
{
    private static final long FIVE_MINUTES_MILLIS = 5 * 60 * 1000L;
    private static final Pattern EDGE_NUMBER = Pattern.compile("\\D++(\\d++)$");

    @Resource
    private EmsDeviceComponentMapper deviceComponentMapper;

    @Resource
    private EmsChannelMappingMapper channelMappingMapper;

    @Resource
    private EmsMetricHistory5MinMapper metricHistory5MinMapper;

    @Resource
    private EmsMetricSyncCursorMapper metricSyncCursorMapper;

    @Resource
    private EmsSyncFailureLogService syncFailureLogService;

    @Resource
    private EmsMetricProperties metricProperties;

    @Resource
    private OpenemsInfluxQueryClient influxQueryClient;

    @Resource
    private EmsOpenemsEndpointSourceMapper endpointSourceMapper;

    @Resource
    private EmsServerEndpointMapper serverEndpointMapper;

    @Transactional(rollbackFor = Exception.class)
    public int syncBucket(Date bucketTime, Long stationId)
    {
        Date effectiveBucketTime = effectiveStartTime(bucketTime);
        if (effectiveBucketTime == null || effectiveBucketTime.after(bucketTime))
        {
            return 0;
        }
        Map<String, HistoryCandidate> selected = new LinkedHashMap<String, HistoryCandidate>();
        for (EmsDeviceComponent component : activeBindings(stationId))
        {
            try
            {
                for (HistoryCandidate candidate : collectBindingCandidates(component, bucketTime))
                {
                    String key = candidate.component.getDeviceId() + ":" + candidate.mapping.getMetricKey();
                    HistoryCandidate current = selected.get(key);
                    if (current == null || isPreferred(candidate, current))
                    {
                        selected.put(key, candidate);
                    }
                }
                upsertCursor(component, bucketTime, "IDLE", null);
            }
            catch (Exception ex)
            {
                upsertCursor(component, null, "FAILED", ex.getMessage());
                recordFailure(component, bucketTime, ex.getMessage());
            }
        }
        return persistCandidates(selected.values());
    }

    public int syncBindingBucket(EmsDeviceComponent component, Date bucketTime)
    {
        try
        {
            Date effectiveBucketTime = effectiveStartTime(bucketTime);
            if (effectiveBucketTime == null || effectiveBucketTime.after(bucketTime))
            {
                return 0;
            }
            List<HistoryCandidate> candidates = collectBindingCandidates(component, bucketTime);
            upsertCursor(component, bucketTime, "IDLE", null);
            return persistCandidates(candidates);
        }
        catch (Exception ex)
        {
            upsertCursor(component, null, "FAILED", ex.getMessage());
            recordFailure(component, bucketTime, ex.getMessage());
            return 0;
        }
    }

    public List<Date> findMissingBuckets(Date rangeStartTime, Date rangeEndTime, Long stationId)
    {
        Date startTime = effectiveStartTime(rangeStartTime);
        Date endTime = floorToFiveMinutes(rangeEndTime);
        List<Date> result = new ArrayList<Date>();
        if (startTime == null || endTime == null || !startTime.before(endTime))
        {
            return result;
        }
        List<EmsDeviceComponent> components = activeBindings(stationId);
        if (components.isEmpty())
        {
            return result;
        }
        Map<Long, Set<String>> expectedMetrics = new LinkedHashMap<Long, Set<String>>();
        for (EmsDeviceComponent component : components)
        {
            if (isEdgeBinding(component))
            {
                continue;
            }
            Set<String> keys = expectedMetrics.computeIfAbsent(component.getId(), ignored -> new HashSet<String>());
            for (EmsChannelMapping mapping : mappings(component))
            {
                keys.add(mapping.getMetricKey());
            }
        }
        Set<String> covered = new HashSet<String>();
        for (Map<String, Object> row : metricHistory5MinMapper.selectCoverage(
                EmsRequestSupport.currentTenantId(), startTime, endTime))
        {
            String quality = String.valueOf(row.get("quality"));
            if ("GOOD".equalsIgnoreCase(quality) || "PARTIAL".equalsIgnoreCase(quality))
            {
                Date bucketTime = (Date) row.get("bucketTime");
                covered.add(row.get("deviceId") + ":" + row.get("metricKey") + ":" + bucketTime.getTime());
            }
        }
        for (Date bucketTime = startTime; bucketTime.before(endTime);
             bucketTime = new Date(bucketTime.getTime() + FIVE_MINUTES_MILLIS))
        {
            boolean missing = false;
            Set<String> expectedForBucket = new HashSet<String>();
            for (EmsDeviceComponent component : components)
            {
                if (component.getBindTime() != null && bucketTime.before(component.getBindTime()))
                {
                    continue;
                }
                Set<String> metricKeys = expectedMetrics.get(component.getId());
                if (metricKeys == null)
                {
                    continue;
                }
                for (String metricKey : metricKeys)
                {
                    expectedForBucket.add(component.getDeviceId() + ":" + metricKey);
                }
            }
            for (String expected : expectedForBucket)
            {
                if (!covered.contains(expected + ":" + bucketTime.getTime()))
                {
                    missing = true;
                    break;
                }
            }
            if (missing)
            {
                result.add(bucketTime);
            }
        }
        return result;
    }

    private List<HistoryCandidate> collectBindingCandidates(EmsDeviceComponent component, Date bucketTime)
    {
        List<HistoryCandidate> candidates = new ArrayList<HistoryCandidate>();
        if (isEdgeBinding(component) || (component.getBindTime() != null && bucketTime.before(component.getBindTime())))
        {
            return candidates;
        }
        Date bucketEndTime = new Date(bucketTime.getTime() + FIVE_MINUTES_MILLIS);
        List<EmsChannelMapping> mappings = mappings(component);
        if (mappings.isEmpty())
        {
            return candidates;
        }
        List<String> channels = new ArrayList<String>();
        for (EmsChannelMapping mapping : mappings)
        {
            channels.add(EmsOpenemsChannelSupport.channelAddress(component, mapping));
        }
        EmsOpenemsEndpointSource rawSource = rawSource(component);
        Map<String, Object> rawSeries = querySeries(rawSource, component.getEdgeId(), channels,
                bucketTime.toInstant(), bucketEndTime.toInstant(), mappings);
        for (EmsChannelMapping mapping : mappings)
        {
            String channelAddress = EmsOpenemsChannelSupport.channelAddress(component, mapping);
            List<BigDecimal> values = asDecimalList(rawSeries.get(channelAddress));
            AggregatedMetric metric = aggregate(values, mapping.getSampleMethod());
            metric = deltaFallback(component, mapping, channelAddress, bucketTime, bucketEndTime, values, metric);
            candidates.add(new HistoryCandidate(component, mapping, bucketTime,
                    metric.value.multiply(mapping.getScaleFactor()), metric.quality, metric.qualityReason));
        }
        return candidates;
    }

    private boolean isEdgeBinding(EmsDeviceComponent component)
    {
        return component != null
                && ("_edge".equalsIgnoreCase(component.getComponentId())
                || "EDGE".equalsIgnoreCase(component.getComponentType()));
    }

    private void recordFailure(EmsDeviceComponent component, Date bucketTime, String errorMessage)
    {
        syncFailureLogService.record(component, "OPENEMS_HISTORY_5MIN",
                component.getId() + ":" + formatTime(bucketTime), errorMessage);
    }

    private List<EmsDeviceComponent> activeBindings(Long stationId)
    {
        LambdaQueryWrapper<EmsDeviceComponent> wrapper = new LambdaQueryWrapper<EmsDeviceComponent>()
                .eq(EmsDeviceComponent::getTenantId, EmsRequestSupport.currentTenantId())
                .eq(EmsDeviceComponent::getBindStatus, "ACTIVE")
                .eq(EmsDeviceComponent::getEnabled, "0")
                .eq(EmsDeviceComponent::getDelFlag, "0");
        if (stationId != null)
        {
            wrapper.eq(EmsDeviceComponent::getStationId, stationId);
        }
        return deviceComponentMapper.selectList(wrapper);
    }

    private List<EmsChannelMapping> mappings(EmsDeviceComponent component)
    {
        List<EmsChannelMapping> rows = channelMappingMapper.selectList(new LambdaQueryWrapper<EmsChannelMapping>()
                .eq(EmsChannelMapping::getEnabled, "0")
                .and(wrapper -> wrapper.isNull(EmsChannelMapping::getDeviceType).or().eq(EmsChannelMapping::getDeviceType, component.getComponentType()))
                .orderByAsc(EmsChannelMapping::getSourcePriority));
        List<EmsChannelMapping> result = new ArrayList<EmsChannelMapping>();
        for (EmsChannelMapping row : rows)
        {
            if (EmsOpenemsChannelSupport.matches(component, row))
            {
                EmsChannelMappingMethodSupport.sampleMethod(row.getSampleMethod());
                EmsChannelMappingMethodSupport.reportMethod(row.getReportMethod());
                result.add(row);
            }
        }
        return result;
    }

    static AggregatedMetric aggregate(List<BigDecimal> values, String method)
    {
        if (values.isEmpty())
        {
            return new AggregatedMetric(BigDecimal.ZERO, "MISSING", "OpenEMS历史序列未返回有效采样值");
        }
        String normalizedMethod = EmsChannelMappingMethodSupport.sampleMethod(method);
        if ("LAST".equals(normalizedMethod))
        {
            return good(values.get(values.size() - 1));
        }
        if ("MAX".equals(normalizedMethod))
        {
            BigDecimal max = values.get(0);
            for (BigDecimal value : values)
            {
                max = max.max(value);
            }
            return good(max);
        }
        if ("MIN".equals(normalizedMethod))
        {
            BigDecimal min = values.get(0);
            for (BigDecimal value : values)
            {
                min = min.min(value);
            }
            return good(min);
        }
        if ("DELTA".equals(normalizedMethod))
        {
            BigDecimal delta = values.get(values.size() - 1).subtract(values.get(0));
            if (delta.compareTo(BigDecimal.ZERO) < 0)
            {
                return new AggregatedMetric(BigDecimal.ZERO, "BAD", "OpenEMS历史累计值回退，疑似电表复位或数据回卷");
            }
            return good(delta);
        }
        if ("PERIOD_SUM".equals(normalizedMethod))
        {
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal value : values)
            {
                sum = sum.add(value);
            }
            return good(sum);
        }
        if ("COUNT".equals(normalizedMethod))
        {
            return good(new BigDecimal(values.size()));
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : values)
        {
            sum = sum.add(value);
        }
        return good(sum.divide(new BigDecimal(values.size()), 8, RoundingMode.HALF_UP));
    }

    private AggregatedMetric deltaFallback(EmsDeviceComponent component, EmsChannelMapping mapping, String channelAddress,
                                           Date bucketTime, Date bucketEndTime, List<BigDecimal> bucketValues,
                                           AggregatedMetric originalMetric)
    {
        if (!"DELTA".equals(EmsChannelMappingMethodSupport.sampleMethod(mapping.getSampleMethod())))
        {
            return originalMetric;
        }
        if (bucketValues.size() >= 2)
        {
            return originalMetric;
        }
        try
        {
            Date previousBucketTime = new Date(bucketTime.getTime() - FIVE_MINUTES_MILLIS);
            List<String> channels = new ArrayList<String>();
            channels.add(channelAddress);
            EmsOpenemsEndpointSource rawSource = rawSource(component);
            List<EmsChannelMapping> selectedMappings = new ArrayList<EmsChannelMapping>();
            selectedMappings.add(mapping);
            Map<String, Object> rawSeries = querySeries(rawSource, component.getEdgeId(), channels,
                    previousBucketTime.toInstant(), bucketEndTime.toInstant(), selectedMappings);
            List<BigDecimal> crossBucketValues = asDecimalList(rawSeries.get(channelAddress));
            if (crossBucketValues.size() < 2)
            {
                return new AggregatedMetric(BigDecimal.ZERO, "MISSING",
                        "OpenEMS历史累计通道桶内仅" + bucketValues.size() + "个采样点，且未找到上一桶基准值");
            }
            BigDecimal latest = crossBucketValues.get(crossBucketValues.size() - 1);
            BigDecimal previous = crossBucketValues.get(crossBucketValues.size() - 2);
            BigDecimal delta = latest.subtract(previous);
            if (delta.compareTo(BigDecimal.ZERO) < 0)
            {
                return new AggregatedMetric(BigDecimal.ZERO, "BAD", "OpenEMS历史累计值回退，疑似电表复位或数据回卷");
            }
            return new AggregatedMetric(delta, "PARTIAL",
                    "OpenEMS历史累计通道桶内采样不足，已使用上一桶累计值计算跨桶差值");
        }
        catch (Exception ex)
        {
            return new AggregatedMetric(originalMetric.value, "PARTIAL",
                    originalMetric.qualityReason + "；跨桶累计差值兜底失败：" + ex.getMessage());
        }
    }

    private static AggregatedMetric good(BigDecimal value)
    {
        return new AggregatedMetric(value, "GOOD", "OpenEMS历史采样聚合成功");
    }

    private Map<String, Object> querySeries(EmsOpenemsEndpointSource source, String edgeId, List<String> channels,
                                            Instant from, Instant to, List<EmsChannelMapping> mappings)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String edgeKey = edgeKey(edgeId);
        int offsetSeconds = ZoneId.of(source.getTimezone()).getRules().getOffset(from).getTotalSeconds();
        for (int index = 0; index < channels.size(); index++)
        {
            EmsChannelMapping mapping = mappings.get(Math.min(index, mappings.size() - 1));
            String aggregation = influxAggregation(mapping.getSampleMethod());
            List<OpenemsInfluxQueryClient.Sample> samples = influxQueryClient.queryHistory(source, edgeKey,
                    channels.get(index), source.getMeasurement(), source.getRetentionPolicy(), from, to,
                    300, aggregation, offsetSeconds);
            List<BigDecimal> values = new ArrayList<BigDecimal>();
            for (OpenemsInfluxQueryClient.Sample sample : samples)
            {
                Object value = sample.getValue();
                if (value instanceof Number)
                {
                    values.add(new BigDecimal(String.valueOf(value)));
                }
            }
            result.put(channels.get(index), values);
        }
        return result;
    }

    private String influxAggregation(String sampleMethod)
    {
        String method = EmsChannelMappingMethodSupport.sampleMethod(sampleMethod);
        if ("MAX".equals(method)) return "MAX";
        if ("MIN".equals(method)) return "MIN";
        if ("LAST".equals(method) || "DELTA".equals(method)) return "LAST";
        if ("PERIOD_SUM".equals(method)) return "SUM";
        if ("COUNT".equals(method)) return "COUNT";
        return "MEAN";
    }

    private EmsOpenemsEndpointSource rawSource(EmsDeviceComponent component)
    {
        EmsServerEndpoint endpoint = serverEndpointMapper.selectOne(new LambdaQueryWrapper<EmsServerEndpoint>()
                .eq(EmsServerEndpoint::getTenantId, component.getTenantId())
                .eq(EmsServerEndpoint::getId, component.getServerEndpointId())
                .eq(EmsServerEndpoint::getEnabled, "0")
                .last("limit 1"));
        if (endpoint == null) throw new IllegalStateException("OpenEMS端点已停用，不再同步数据");
        EmsOpenemsEndpointSource source = endpointSourceMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsEndpointSource>()
                .eq(EmsOpenemsEndpointSource::getTenantId, component.getTenantId())
                .eq(EmsOpenemsEndpointSource::getEndpointId, component.getServerEndpointId())
                .eq(EmsOpenemsEndpointSource::getSourceType, "RAW_INFLUX")
                .eq(EmsOpenemsEndpointSource::getEnabled, "0")
                .eq(EmsOpenemsEndpointSource::getDelFlag, "0")
                .last("limit 1"));
        if (source == null) throw new IllegalStateException("端点未配置并启用Raw Influx");
        return source;
    }

    private String edgeKey(String edgeId)
    {
        Matcher matcher = EDGE_NUMBER.matcher(String.valueOf(edgeId));
        if (!matcher.matches()) throw new IllegalStateException("Edge ID末尾必须包含数字，无法映射Influx edge标签：" + edgeId);
        return matcher.group(1);
    }

    private List<BigDecimal> asDecimalList(Object value)
    {
        List<BigDecimal> result = new ArrayList<BigDecimal>();
        if (!(value instanceof List))
        {
            if (value instanceof Number)
            {
                result.add(new BigDecimal(String.valueOf(value)));
            }
            return result;
        }
        for (Object item : (List<?>) value)
        {
            if (item instanceof Number)
            {
                result.add(new BigDecimal(String.valueOf(item)));
            }
        }
        return result;
    }

    private int persistCandidates(Iterable<HistoryCandidate> candidates)
    {
        int usableRows = 0;
        for (HistoryCandidate candidate : candidates)
        {
            upsertHistory(candidate);
            if ("GOOD".equals(candidate.quality) || "PARTIAL".equals(candidate.quality))
            {
                usableRows++;
            }
        }
        return usableRows;
    }

    private void upsertHistory(HistoryCandidate candidate)
    {
        EmsDeviceComponent component = candidate.component;
        EmsChannelMapping mapping = candidate.mapping;
        EmsMetricHistory5Min history = new EmsMetricHistory5Min();
        history.setTenantId(component.getTenantId());
        history.setCompanyId(component.getCompanyId());
        history.setStationId(component.getStationId());
        history.setDeviceId(component.getDeviceId());
        history.setDeviceComponentId(component.getId());
        history.setServerEndpointId(component.getServerEndpointId());
        history.setEdgeId(component.getEdgeId());
        history.setComponentId(component.getComponentId());
        history.setSerialNo(component.getSerialNo());
        history.setMetricKey(mapping.getMetricKey());
        history.setReportMethod(EmsChannelMappingMethodSupport.reportMethod(mapping.getReportMethod()));
        history.setSourceRole(mapping.getSourceRole());
        history.setSourcePriority(mapping.getSourcePriority());
        history.setBucketTime(candidate.bucketTime);
        history.setMetricValue(candidate.value);
        history.setUnit(mapping.getUnit());
        history.setQuality(candidate.quality);
        history.setQualityReason(candidate.qualityReason);
        history.setSourceSampleTime(candidate.bucketTime);
        Date now = new Date();
        history.setCreateTime(now);
        history.setUpdateTime(now);
        metricHistory5MinMapper.upsert(history);
    }

    private boolean isPreferred(HistoryCandidate candidate, HistoryCandidate current)
    {
        int qualityCompare = Integer.compare(qualityRank(candidate.quality), qualityRank(current.quality));
        if (qualityCompare != 0)
        {
            return qualityCompare < 0;
        }
        int priorityCompare = Integer.compare(sourcePriority(candidate.mapping), sourcePriority(current.mapping));
        if (priorityCompare != 0)
        {
            return priorityCompare < 0;
        }
        return longValue(candidate.component.getId()) < longValue(current.component.getId());
    }

    private int qualityRank(String quality)
    {
        if ("GOOD".equalsIgnoreCase(quality)) return 0;
        if ("PARTIAL".equalsIgnoreCase(quality)) return 1;
        if ("MISSING".equalsIgnoreCase(quality)) return 2;
        return 3;
    }

    private int sourcePriority(EmsChannelMapping mapping)
    {
        return mapping.getSourcePriority() == null ? 100 : mapping.getSourcePriority();
    }

    private long longValue(Long value)
    {
        return value == null ? Long.MAX_VALUE : value.longValue();
    }

    private Date effectiveStartTime(Date requestedStartTime)
    {
        if (requestedStartTime == null)
        {
            return null;
        }
        Date startTime = ceilToFiveMinutes(requestedStartTime);
        String configured = metricProperties.getStatisticsStartTime();
        if (configured == null || configured.trim().isEmpty())
        {
            return startTime;
        }
        try
        {
            Date configuredStart = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(configured.trim());
            Date alignedConfiguredStart = ceilToFiveMinutes(configuredStart);
            return alignedConfiguredStart.after(startTime) ? alignedConfiguredStart : startTime;
        }
        catch (ParseException ex)
        {
            throw new IllegalStateException("ems.metric.statistics-start-time 格式应为 yyyy-MM-dd HH:mm:ss", ex);
        }
    }

    private Date floorToFiveMinutes(Date value)
    {
        if (value == null)
        {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(value);
        calendar.set(Calendar.MINUTE, calendar.get(Calendar.MINUTE) - calendar.get(Calendar.MINUTE) % 5);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    private Date ceilToFiveMinutes(Date value)
    {
        Date floor = floorToFiveMinutes(value);
        return floor.before(value) ? new Date(floor.getTime() + FIVE_MINUTES_MILLIS) : floor;
    }

    static final class AggregatedMetric
    {
        final BigDecimal value;
        final String quality;
        final String qualityReason;

        private AggregatedMetric(BigDecimal value, String quality, String qualityReason)
        {
            this.value = value;
            this.quality = quality;
            this.qualityReason = qualityReason;
        }
    }

    static final class HistoryCandidate
    {
        final EmsDeviceComponent component;
        final EmsChannelMapping mapping;
        final Date bucketTime;
        final BigDecimal value;
        final String quality;
        final String qualityReason;

        private HistoryCandidate(EmsDeviceComponent component, EmsChannelMapping mapping, Date bucketTime,
                                 BigDecimal value, String quality, String qualityReason)
        {
            this.component = component;
            this.mapping = mapping;
            this.bucketTime = bucketTime;
            this.value = value;
            this.quality = quality;
            this.qualityReason = qualityReason;
        }
    }

    private void upsertCursor(EmsDeviceComponent component, Date bucketTime, String status, String errorMessage)
    {
        EmsMetricSyncCursor cursor = metricSyncCursorMapper.selectOne(new LambdaQueryWrapper<EmsMetricSyncCursor>()
                .eq(EmsMetricSyncCursor::getTenantId, component.getTenantId())
                .eq(EmsMetricSyncCursor::getDeviceComponentId, component.getId())
                .last("limit 1"));
        if (cursor == null)
        {
            cursor = new EmsMetricSyncCursor();
            cursor.setTenantId(component.getTenantId());
            cursor.setDeviceComponentId(component.getId());
            cursor.setCreateTime(new Date());
        }
        if (bucketTime != null)
        {
            cursor.setLastSuccessBucketTime(bucketTime);
        }
        cursor.setLastAttemptTime(new Date());
        cursor.setStatus(status);
        cursor.setErrorMessage(errorMessage);
        if (cursor.getId() == null)
        {
            metricSyncCursorMapper.insert(cursor);
        }
        else
        {
            metricSyncCursorMapper.updateById(cursor);
        }
    }

    private String formatTime(Date date)
    {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
    }
}
