package com.witos.ems.server.mapper;

import com.witos.common.mybatisplus.mapper.BaseMapperX;
import com.witos.ems.server.domain.entity.EmsMetricHistory5Min;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface EmsMetricHistory5MinMapper extends BaseMapperX<EmsMetricHistory5Min>
{
    int upsert(@Param("row") EmsMetricHistory5Min row);

    List<Map<String, Object>> selectCoverage(@Param("tenantId") Long tenantId,
                                             @Param("startTime") Date startTime,
                                             @Param("endTime") Date endTime);
}
