package com.witos.ems.server.openems;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.ems.server.domain.entity.EmsChannelMapping;
import com.witos.ems.server.domain.entity.EmsDevice;
import com.witos.ems.server.domain.entity.EmsDeviceComponent;
import com.witos.ems.server.domain.entity.EmsMetricSnapshot;
import com.witos.ems.server.domain.entity.EmsOpenemsEdge;
import com.witos.ems.server.domain.entity.EmsOpenemsEndpointSource;
import com.witos.ems.server.domain.entity.EmsServerEndpoint;
import com.witos.ems.server.mapper.EmsChannelMappingMapper;
import com.witos.ems.server.mapper.EmsDeviceComponentMapper;
import com.witos.ems.server.mapper.EmsDeviceMapper;
import com.witos.ems.server.mapper.EmsMetricSnapshotMapper;
import com.witos.ems.server.mapper.EmsOpenemsEdgeMapper;
import com.witos.ems.server.mapper.EmsOpenemsEndpointSourceMapper;
import com.witos.ems.server.mapper.EmsServerEndpointMapper;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EmsOpenemsRealtimeSyncService
{
    private static final Pattern EDGE_NUMBER = Pattern.compile("\\D++(\\d++)$");
    @Resource
    private EmsDeviceComponentMapper deviceComponentMapper;

    @Resource
    private EmsDeviceMapper deviceMapper;

    @Resource
    private EmsChannelMappingMapper channelMappingMapper;

    @Resource
    private EmsMetricSnapshotMapper metricSnapshotMapper;

    @Resource
    private EmsSyncFailureLogService syncFailureLogService;

    @Resource
    private OpenemsInfluxQueryClient influxQueryClient;

    @Resource
    private EmsOpenemsEndpointSourceMapper endpointSourceMapper;

    @Resource
    private EmsOpenemsEdgeMapper openemsEdgeMapper;

    @Resource
    private EmsServerEndpointMapper serverEndpointMapper;

    @Transactional(rollbackFor = Exception.class)
    public int syncActiveBindings(Long stationId)
    {
        int affectedRows = 0;
        for (EmsDeviceComponent component : activeBindings(stationId))
        {
            try
            {
                affectedRows += syncBinding(component);
            }
            catch (Exception ex)
            {
                insertSyncLog(component, ex.getMessage());
            }
        }
        return affectedRows;
    }

    private void insertSyncLog(EmsDeviceComponent component, String errorMessage)
    {
        syncFailureLogService.record(component, "OPENEMS_REALTIME", String.valueOf(component.getId()), errorMessage);
    }

    public int syncBinding(EmsDeviceComponent component)
    {
        if (isEdgeBinding(component))
        {
            return syncEdgeBinding(component);
        }
        List<EmsChannelMapping> mappings = mappings(component);
        List<String> channels = new ArrayList<String>();
        for (EmsChannelMapping mapping : mappings)
        {
            channels.add(EmsOpenemsChannelSupport.channelAddress(component, mapping));
        }
        EmsOpenemsEndpointSource rawSource = rawSource(component);
        String edgeKey = edgeKey(component.getEdgeId());
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (String channel : channels)
        {
            OpenemsInfluxQueryClient.Sample sample = influxQueryClient.queryLatest(rawSource, edgeKey, channel);
            values.put(channel, sample == null ? null : sample.getValue());
        }
        int affectedRows = 0;
        Date now = new Date();
        for (EmsChannelMapping mapping : mappings)
        {
            String channelAddress = EmsOpenemsChannelSupport.channelAddress(component, mapping);
            Object rawValue = values.get(channelAddress);
            upsertSnapshot(component, mapping, rawValue, now);
            affectedRows++;
        }
        component.setLastSampleTime(now);
        component.setUpdateTime(now);
        deviceComponentMapper.updateById(component);
        return affectedRows;
    }

    private int syncEdgeBinding(EmsDeviceComponent component)
    {
        EmsOpenemsEdge edge = openemsEdgeMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsEdge>()
                .eq(EmsOpenemsEdge::getTenantId, component.getTenantId())
                .eq(EmsOpenemsEdge::getEndpointId, component.getServerEndpointId())
                .eq(EmsOpenemsEdge::getEdgeId, component.getEdgeId())
                .eq(EmsOpenemsEdge::getDelFlag, "0")
                .last("limit 1"));
        boolean online = edge != null && "ONLINE".equals(edge.getOnlineStatus());
        Date now = new Date();

        EmsDevice device = deviceMapper.selectById(component.getDeviceId());
        if (device != null)
        {
            device.setCommStatus(online ? "ONLINE" : "OFFLINE");
            if (online)
            {
                device.setLastHeartbeatTime(now);
            }
            device.setUpdateTime(now);
            deviceMapper.updateById(device);
        }

        if (online)
        {
            component.setLastSampleTime(now);
        }
        component.setUpdateTime(now);
        deviceComponentMapper.updateById(component);
        return 1;
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
        if (source == null)
        {
            throw new IllegalStateException("端点未配置并启用Raw Influx");
        }
        return source;
    }

    private String edgeKey(String edgeId)
    {
        Matcher matcher = EDGE_NUMBER.matcher(String.valueOf(edgeId));
        if (!matcher.matches())
        {
            throw new IllegalStateException("Edge ID末尾必须包含数字，无法映射Influx edge标签：" + edgeId);
        }
        return matcher.group(1);
    }

    private boolean isEdgeBinding(EmsDeviceComponent component)
    {
        return component != null
                && ("_edge".equalsIgnoreCase(component.getComponentId())
                || "EDGE".equalsIgnoreCase(component.getComponentType()));
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

    private void upsertSnapshot(EmsDeviceComponent component, EmsChannelMapping mapping, Object rawValue, Date sampleTime)
    {
        metricSnapshotMapper.delete(new LambdaQueryWrapper<EmsMetricSnapshot>()
                .eq(EmsMetricSnapshot::getTenantId, component.getTenantId())
                .eq(EmsMetricSnapshot::getStationId, component.getStationId())
                .eq(EmsMetricSnapshot::getDeviceId, component.getDeviceId())
                .eq(EmsMetricSnapshot::getMetricKey, mapping.getMetricKey()));
        EmsMetricSnapshot snapshot = new EmsMetricSnapshot();
        snapshot.setTenantId(component.getTenantId());
        snapshot.setCompanyId(component.getCompanyId());
        snapshot.setStationId(component.getStationId());
        snapshot.setDeviceId(component.getDeviceId());
        snapshot.setMetricKey(mapping.getMetricKey());
        snapshot.setUnit(mapping.getUnit());
        snapshot.setSampleTime(sampleTime);
        snapshot.setQuality(rawValue == null ? "MISSING" : "GOOD");
        snapshot.setQualityReason(rawValue == null ? "OpenEMS当前值未返回" : "OpenEMS实时采样成功");
        if (rawValue instanceof Number)
        {
            snapshot.setMetricValue(new BigDecimal(String.valueOf(rawValue)).multiply(mapping.getScaleFactor()));
        }
        else if (rawValue != null)
        {
            snapshot.setMetricText(String.valueOf(rawValue));
        }
        metricSnapshotMapper.insert(snapshot);
    }
}
