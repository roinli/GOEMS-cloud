package com.witos.ems.server.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.witos.common.mybatisplus.mapper.BaseMapperX;
import com.witos.ems.server.domain.entity.EmsChannelMapping;

@InterceptorIgnore(tenantLine = "1")
public interface EmsChannelMappingMapper extends BaseMapperX<EmsChannelMapping>
{
}
