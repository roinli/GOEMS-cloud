package com.witos.ems.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.witos.ems.server.domain.entity.EmsReportSyncTask;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

public interface EmsReportSyncTaskMapper extends BaseMapper<EmsReportSyncTask>
{
    int claimTask(@Param("id") Long id,
                  @Param("tenantId") Long tenantId,
                  @Param("allowSuccess") boolean allowSuccess,
                  @Param("retryIncrement") int retryIncrement,
                  @Param("username") String username,
                  @Param("executeStartTime") Date executeStartTime);

    int finishTask(@Param("id") Long id,
                   @Param("tenantId") Long tenantId,
                   @Param("taskStatus") String taskStatus,
                   @Param("affectedRows") int affectedRows,
                   @Param("errorMessage") String errorMessage,
                   @Param("username") String username,
                   @Param("executeEndTime") Date executeEndTime);

    int deleteStationHourRows(@Param("tenantId") Long tenantId,
                              @Param("companyId") Long companyId,
                              @Param("stationId") Long stationId,
                              @Param("startTime") String startTime,
                              @Param("endTime") String endTime);

    int insertStationHourRowsFromDeviceHour(@Param("tenantId") Long tenantId,
                                            @Param("companyId") Long companyId,
                                            @Param("stationId") Long stationId,
                                            @Param("startTime") String startTime,
                                            @Param("endTime") String endTime);

    List<Map<String, Object>> selectStationHourRows(@Param("tenantId") Long tenantId,
                                                    @Param("companyId") Long companyId,
                                                    @Param("stationId") Long stationId,
                                                    @Param("startTime") String startTime,
                                                    @Param("endTime") String endTime);

    int updateStationHourDerivedValues(@Param("id") Long id,
                                       @Param("revenueAmount") BigDecimal revenueAmount,
                                       @Param("feedInRevenue") BigDecimal feedInRevenue,
                                       @Param("selfUseSaving") BigDecimal selfUseSaving,
                                       @Param("storageArbitrageRevenue") BigDecimal storageArbitrageRevenue,
                                       @Param("purchaseCost") BigDecimal purchaseCost,
                                       @Param("revenueQualityReason") String revenueQualityReason,
                                       @Param("equivalentHours") BigDecimal equivalentHours,
                                       @Param("co2ReductionKg") BigDecimal co2ReductionKg,
                                       @Param("standardCoalKg") BigDecimal standardCoalKg,
                                       @Param("equivalentTrees") BigDecimal equivalentTrees);

    int deleteDeviceHourRows(@Param("deviceType") String deviceType,
                             @Param("tenantId") Long tenantId,
                             @Param("companyId") Long companyId,
                             @Param("stationId") Long stationId,
                             @Param("startTime") String startTime,
                             @Param("endTime") String endTime);

    int insertDeviceHourRowsFrom5Min(@Param("deviceType") String deviceType,
                                     @Param("tenantId") Long tenantId,
                                     @Param("companyId") Long companyId,
                                     @Param("stationId") Long stationId,
                                     @Param("startTime") String startTime,
                                     @Param("endTime") String endTime);

    int deleteStationAggregateRows(@Param("periodType") String periodType,
                                   @Param("tenantId") Long tenantId,
                                   @Param("companyId") Long companyId,
                                   @Param("stationId") Long stationId,
                                   @Param("startTime") String startTime,
                                   @Param("endTime") String endTime);

    int insertStationAggregateRows(@Param("periodType") String periodType,
                                   @Param("tenantId") Long tenantId,
                                   @Param("companyId") Long companyId,
                                   @Param("stationId") Long stationId,
                                   @Param("startTime") String startTime,
                                   @Param("endTime") String endTime);

    int deleteDeviceAggregateRows(@Param("periodType") String periodType,
                                  @Param("deviceType") String deviceType,
                                  @Param("tenantId") Long tenantId,
                                  @Param("companyId") Long companyId,
                                  @Param("stationId") Long stationId,
                                  @Param("startTime") String startTime,
                                  @Param("endTime") String endTime);

    int insertDeviceAggregateRows(@Param("periodType") String periodType,
                                  @Param("deviceType") String deviceType,
                                  @Param("tenantId") Long tenantId,
                                  @Param("companyId") Long companyId,
                                  @Param("stationId") Long stationId,
                                  @Param("startTime") String startTime,
                                  @Param("endTime") String endTime);
}
