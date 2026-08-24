package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.witos.ems.server.domain.entity.EmsOpenemsDevice;
import com.witos.ems.server.domain.entity.EmsOpenemsEndpointSource;
import com.witos.ems.server.domain.entity.EmsStation;
import com.witos.ems.server.mapper.EmsOpenemsDeviceMapper;
import com.witos.ems.server.mapper.EmsOpenemsEndpointSourceMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.ems.server.openems.OpenemsInfluxQueryClient;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsOpenemsTimeseriesServiceImplTest
{
    @BeforeAll
    static void initializeLambdaMetadata()
    {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), EmsOpenemsDevice.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), EmsOpenemsEndpointSource.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), EmsStation.class);
    }

    @Mock
    private EmsOpenemsDeviceMapper deviceMapper;
    @Mock
    private EmsOpenemsEndpointSourceMapper sourceMapper;
    @Mock
    private EmsStationMapper stationMapper;
    @Mock
    private OpenemsInfluxQueryClient queryClient;
    @InjectMocks
    private EmsOpenemsTimeseriesServiceImpl service;

    private EmsOpenemsDevice device;
    private EmsOpenemsEndpointSource api;
    private EmsOpenemsEndpointSource raw;

    @BeforeEach
    void setUp()
    {
        device = new EmsOpenemsDevice();
        device.setId(11L);
        device.setTenantId(9999L);
        device.setEndpointId(10L);
        device.setEdgeId("edge7");
        device.setPrimaryComponentId("meter0");
        device.setDelFlag("0");
        api = source("API", "data");
        api.setTimezone("Asia/Shanghai");
        raw = source("RAW_INFLUX", "data");
        when(deviceMapper.selectOne(any())).thenReturn(device);
    }

    @Test
    void latestMarksSamplesOlderThanTenMinutesAsStale()
    {
        when(sourceMapper.selectList(any())).thenReturn(Arrays.asList(api, raw));
        when(queryClient.queryLatest(raw, "7", "meter0/ActivePower"))
                .thenReturn(new OpenemsInfluxQueryClient.Sample(new Date(System.currentTimeMillis() - 601000L), 12L));

        Map<String, Object> result = service.latest(11L, query("channels", "ActivePower"));

        assertEquals("STALE", result.get("quality"));
        assertEquals("RAW", result.get("source"));
        assertEquals("Asia/Shanghai", result.get("timezone"));
    }

    @Test
    void historyFallsBackToRawWhenAggregatedRangeIsIncomplete()
    {
        EmsOpenemsEndpointSource aggregated = source("AGGREGATED_INFLUX", "avg");
        aggregated.setTimezone("Asia/Shanghai");
        aggregated.setQueryConfigJson("{\"average\":{\"measurement\":\"avg\",\"channels\":[\"meter0/ActivePower\"],\"timezones\":[\"Asia/Shanghai\"]}}");
        when(sourceMapper.selectList(any())).thenReturn(Arrays.asList(api, raw, aggregated));
        when(queryClient.queryHistory(eq(aggregated), eq("7"), anyString(), eq("avg"), any(), any(), any(),
                eq(300), eq("MEAN"), anyInt())).thenReturn(Collections.singletonList(sample("2026-08-07T00:05:00Z", 1L)));
        when(queryClient.queryHistory(eq(raw), eq("7"), anyString(), eq("data"), any(), any(), any(),
                eq(300), eq("MEAN"), anyInt())).thenReturn(Arrays.asList(
                        sample("2026-08-07T00:05:00Z", 1L), sample("2026-08-07T00:10:00Z", 2L)));

        Map<String, Object> result = service.history(11L, historyQuery());

        assertEquals("RAW", result.get("source"));
        assertEquals("AGGREGATED_RANGE_NOT_COVERED", result.get("fallbackReason"));
        assertEquals(2, ((List<?>) result.get("rows")).size());
    }

    @Test
    void historyUsesAggregatedOnlyWhenChannelTimezoneAndRangeAreCovered()
    {
        EmsOpenemsEndpointSource aggregated = source("AGGREGATED_INFLUX", "avg");
        aggregated.setTimezone("Asia/Shanghai");
        aggregated.setQueryConfigJson("{\"average\":{\"measurement\":\"avg\",\"retentionPolicy\":\"rp_avg\","
                + "\"channels\":[\"meter0/ActivePower\"],\"timezones\":[\"Asia/Shanghai\"]}}");
        when(sourceMapper.selectList(any())).thenReturn(Arrays.asList(api, raw, aggregated));
        when(queryClient.queryHistory(eq(aggregated), eq("7"), anyString(), eq("avg"), eq("rp_avg"), any(), any(),
                eq(300), eq("MEAN"), anyInt())).thenReturn(Arrays.asList(
                        sample("2026-08-07T00:05:00Z", 1L), sample("2026-08-07T00:10:00Z", 2L)));

        Map<String, Object> result = service.history(11L, historyQuery());

        assertEquals("AGGREGATED", result.get("source"));
        assertEquals("GOOD", result.get("quality"));
        verify(queryClient, never()).queryHistory(eq(raw), anyString(), anyString(), anyString(), any(), any(), any(),
                anyInt(), anyString(), anyInt());
    }

    @Test
    void cumulativeChannelUsesTimezoneSpecificMaxMeasurement()
    {
        EmsOpenemsEndpointSource aggregated = source("AGGREGATED_INFLUX", "avg");
        aggregated.setTimezone("Asia/Shanghai");
        aggregated.setQueryConfigJson("{\"cumulative\":{\"retentionPolicy\":\"rp_max\","
                + "\"channels\":[\"meter0/ActiveConsumptionEnergy\"],"
                + "\"measurementByTimezone\":{\"Asia/Shanghai\":\"max_cn\"}}}");
        when(sourceMapper.selectList(any())).thenReturn(Arrays.asList(api, raw, aggregated));
        when(queryClient.queryHistory(eq(aggregated), eq("7"), eq("meter0/ActiveConsumptionEnergy"),
                eq("max_cn"), eq("rp_max"), any(), any(), eq(86400), eq("MEAN"), anyInt()))
                .thenReturn(Collections.singletonList(sample("2026-08-07T00:00:00Z", 1200L)));
        Map<String, String> query = new HashMap<String, String>();
        query.put("channels", "ActiveConsumptionEnergy");
        query.put("from", "2026-08-07T00:00:00Z");
        query.put("to", "2026-08-08T00:00:00Z");
        query.put("intervalSeconds", "86400");

        Map<String, Object> result = service.history(11L, query);

        assertEquals("AGGREGATED", result.get("source"));
        assertEquals("GOOD", result.get("quality"));
    }

    @Test
    void nonNumericEdgeReturnsExplicitUnavailableReasonWithoutQueryingInflux()
    {
        device.setEdgeId("site-main");
        when(sourceMapper.selectList(any())).thenReturn(Arrays.asList(api, raw));

        Map<String, Object> result = service.latest(11L, query("channels", "ActivePower"));

        assertEquals(false, result.get("available"));
        assertEquals("TIMESERIES_UNAVAILABLE_EDGE_ID_FORMAT", result.get("reason"));
        verify(queryClient, never()).queryLatest(any(), anyString(), anyString());
    }

    @Test
    void boundDeviceUsesStationTimezone()
    {
        device.setCompanyId(100L);
        device.setStationId(1000L);
        EmsStation station = new EmsStation();
        station.setId(1000L);
        station.setTenantId(9999L);
        station.setCompanyId(100L);
        station.setTimezone("Europe/Berlin");
        station.setDelFlag("0");
        when(stationMapper.selectOne(any())).thenReturn(station);
        when(sourceMapper.selectList(any())).thenReturn(Arrays.asList(api, raw));
        when(queryClient.queryLatest(raw, "7", "meter0/ActivePower"))
                .thenReturn(new OpenemsInfluxQueryClient.Sample(new Date(), 12L));

        Map<String, Object> result = service.latest(11L, query("channels", "ActivePower"));

        assertEquals("Europe/Berlin", result.get("timezone"));
    }

    @Test
    void chargerDefaultsIncludeEnergySession()
    {
        device.setDeviceType("CHARGER");
        device.setPrimaryComponentId("evcs0");
        when(sourceMapper.selectList(any())).thenReturn(Arrays.asList(api, raw));
        when(queryClient.queryLatest(raw, "7", "evcs0/ActivePower"))
                .thenReturn(new OpenemsInfluxQueryClient.Sample(new Date(), 5000L));
        when(queryClient.queryLatest(raw, "7", "evcs0/EnergySession"))
                .thenReturn(new OpenemsInfluxQueryClient.Sample(new Date(), 18100L));
        when(queryClient.queryLatest(raw, "7", "evcs0/ActiveConsumptionEnergy")).thenReturn(null);

        Map<String, Object> result = service.latest(11L, Collections.emptyMap());

        assertEquals(3, ((List<?>) result.get("values")).size());
        verify(queryClient).queryLatest(raw, "7", "evcs0/EnergySession");
    }

    private EmsOpenemsEndpointSource source(String type, String measurement)
    {
        EmsOpenemsEndpointSource source = new EmsOpenemsEndpointSource();
        source.setId((long) type.hashCode());
        source.setTenantId(9999L);
        source.setEndpointId(10L);
        source.setSourceType(type);
        source.setVersion("INFLUX_1");
        source.setMeasurement(measurement);
        source.setTimezone("Asia/Shanghai");
        source.setEnabled("0");
        source.setDelFlag("0");
        return source;
    }

    private OpenemsInfluxQueryClient.Sample sample(String time, Object value)
    {
        return new OpenemsInfluxQueryClient.Sample(Date.from(Instant.parse(time)), value);
    }

    private Map<String, String> historyQuery()
    {
        Map<String, String> query = new HashMap<String, String>();
        query.put("channels", "ActivePower");
        query.put("from", "2026-08-07T00:00:00Z");
        query.put("to", "2026-08-07T00:10:00Z");
        query.put("intervalSeconds", "300");
        return query;
    }

    private Map<String, String> query(String key, String value)
    {
        Map<String, String> query = new HashMap<String, String>();
        query.put(key, value);
        return query;
    }
}
