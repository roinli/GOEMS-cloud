package com.witos.ems.server.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface EmsTenantScheduleMapper
{
    @InterceptorIgnore(tenantLine = "1")
    @Select("select id from sys_tenant where status = 0 and del_flag = '0' and id <> 9999 order by id")
    List<Long> selectEnabledTenantIds();
}