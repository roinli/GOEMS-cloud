package com.witos.ems.server.mapper;

import com.witos.common.mybatisplus.mapper.BaseMapperX;
import com.witos.ems.server.domain.entity.EmsOpenemsCapability;
import org.apache.ibatis.annotations.Update;

public interface EmsOpenemsCapabilityMapper extends BaseMapperX<EmsOpenemsCapability>
{
    @Update("UPDATE ems_openems_capability SET route = #{route}, request_schema = #{requestSchema}, "
            + "response_schema = #{responseSchema}, guards = #{guards}, channel_schema = #{channelSchema}, "
            + "factory_schema = #{factorySchema}, version = #{version}, status = #{status}, "
            + "last_seen_at = #{lastSeenAt}, del_flag = #{delFlag}, update_by = #{updateBy}, "
            + "update_time = #{updateTime}, remark = #{remark} "
            + "WHERE tenant_id = #{tenantId} AND endpoint_id = #{endpointId} AND edge_id = #{edgeId} "
            + "AND component_id = #{componentId} AND capability_key = #{capabilityKey}")
    int updateByUniqueKey(EmsOpenemsCapability capability);
}
