package com.witos.ems.server.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.Date;

public interface EmsDataRetentionMapper
{
    int deleteMetricHistoryBefore(@Param("tenantId") Long tenantId, @Param("beforeTime") Date beforeTime);

    int deleteSyncLogsBefore(@Param("tenantId") Long tenantId, @Param("beforeTime") Date beforeTime);

    int deleteReportTasksBefore(@Param("tenantId") Long tenantId, @Param("beforeTime") Date beforeTime);

    int deleteBackfillTasksBefore(@Param("tenantId") Long tenantId, @Param("beforeTime") Date beforeTime);

    int deleteResourceReportsBefore(@Param("tenantId") Long tenantId, @Param("beforeTime") Date beforeTime);
}
