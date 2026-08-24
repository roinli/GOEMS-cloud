package com.witos.ems.server.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.witos.common.mybatisplus.mapper.BaseMapperX;
import com.witos.ems.server.auth.EmsDataScope;
import com.witos.ems.server.domain.entity.EmsAlarmEvent;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface EmsAlarmEventMapper extends BaseMapperX<EmsAlarmEvent>
{
    IPage<Map<String, Object>> selectAlarmEventPage(Page<Map<String, Object>> page,
                                                    @Param("query") Map<String, Object> query,
                                                    @Param("scope") EmsDataScope scope);

    List<Map<String, Object>> selectAlarmEventList(@Param("query") Map<String, Object> query,
                                                   @Param("scope") EmsDataScope scope);

    List<Map<String, Object>> selectCurrentAlarmList(@Param("query") Map<String, Object> query,
                                                     @Param("scope") EmsDataScope scope);

    Map<String, Object> selectAlarmEventDetail(@Param("id") Long id,
                                               @Param("scope") EmsDataScope scope);

    int ackAlarmEvent(@Param("id") Long id,
                      @Param("tenantId") Long tenantId,
                      @Param("ackBy") String ackBy);

    int clearAlarmEvent(@Param("id") Long id,
                        @Param("tenantId") Long tenantId,
                        @Param("clearBy") String clearBy,
                        @Param("clearType") String clearType);

    EmsAlarmEvent selectActiveMergeTarget(@Param("tenantId") Long tenantId,
                                          @Param("stationId") Long stationId,
                                          @Param("deviceId") Long deviceId,
                                          @Param("alarmCode") String alarmCode);

    List<Long> selectDistinctTenantIdsForHistoryCleanup();

    int cleanupHistoricalEvents(@Param("tenantId") Long tenantId, @Param("beforeTime") java.util.Date beforeTime);
}
