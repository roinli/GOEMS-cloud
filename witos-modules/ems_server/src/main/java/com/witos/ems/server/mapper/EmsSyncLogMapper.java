package com.witos.ems.server.mapper;

import com.witos.common.mybatisplus.mapper.BaseMapperX;
import com.witos.ems.server.domain.entity.EmsSyncLog;
import org.apache.ibatis.annotations.Param;

public interface EmsSyncLogMapper extends BaseMapperX<EmsSyncLog>
{
    int upsertFailure(@Param("row") EmsSyncLog row);
}
