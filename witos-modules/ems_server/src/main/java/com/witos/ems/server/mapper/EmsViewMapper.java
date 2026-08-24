package com.witos.ems.server.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

public interface EmsViewMapper
{
    @InterceptorIgnore(tenantLine = "1")
    List<Map<String, Object>> selectStationRevenueSummary(@Param("stationIds") List<Long> stationIds);

    @InterceptorIgnore(tenantLine = "1")
    List<Map<String, Object>> selectStationRevenueQuality(@Param("stationIds") List<Long> stationIds);

    @InterceptorIgnore(tenantLine = "1")
    List<Map<String, Object>> selectStationHomeMetricSummary(@Param("stationIds") List<Long> stationIds);

    List<Map<String, Object>> selectStationTodayReportSummary(@Param("stationIds") List<Long> stationIds);

    List<Map<String, Object>> selectDeviceTodayReportSummary(@Param("deviceIds") List<Long> deviceIds);

    @InterceptorIgnore(tenantLine = "1")
    List<Map<String, Object>> selectStationDailyTrend(@Param("stationIds") List<Long> stationIds);

    List<Map<String, Object>> selectStationRealtimeTrend(@Param("stationIds") List<Long> stationIds);

    List<Map<String, Object>> selectLatestSampleTimes(@Param("stationIds") List<Long> stationIds);

    @InterceptorIgnore(tenantLine = "1")
    List<Map<String, Object>> selectStationSyncStatus(@Param("stationIds") List<Long> stationIds);

    List<Map<String, Object>> selectActiveAlarmCountByDeviceIds(@Param("deviceIds") List<Long> deviceIds);

    List<Map<String, Object>> selectDeviceSnapshotQualityByDeviceIds(@Param("deviceIds") List<Long> deviceIds);

    List<Map<String, Object>> selectDeviceHierarchyRows(@Param("stationId") Long stationId);

    List<Map<String, Object>> selectDeviceMetricTrend(@Param("deviceId") Long deviceId,
                                                      @Param("metricKeys") List<String> metricKeys);

    @InterceptorIgnore(tenantLine = "1")
    List<Map<String, Object>> selectDeviceMetricHistory(@Param("deviceIds") List<Long> deviceIds,
                                                        @Param("metricKey") String metricKey,
                                                        @Param("startTime") Date startTime,
                                                        @Param("endTime") Date endTime);

    List<Map<String, Object>> selectDeviceStoragePowerTrend(@Param("deviceId") Long deviceId);

    BigDecimal selectDeviceMetricValue(@Param("deviceId") Long deviceId, @Param("metricKey") String metricKey);
}
