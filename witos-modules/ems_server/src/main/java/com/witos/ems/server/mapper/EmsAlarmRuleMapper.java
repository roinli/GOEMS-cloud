package com.witos.ems.server.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.witos.common.mybatisplus.mapper.BaseMapperX;
import com.witos.ems.server.auth.EmsDataScope;
import com.witos.ems.server.domain.entity.EmsAlarmRule;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface EmsAlarmRuleMapper extends BaseMapperX<EmsAlarmRule>
{
    IPage<Map<String, Object>> selectAlarmRulePage(Page<Map<String, Object>> page,
                                                   @Param("query") Map<String, Object> query,
                                                   @Param("scope") EmsDataScope scope);

    Map<String, Object> selectAlarmRuleDetail(@Param("id") Long id,
                                              @Param("scope") EmsDataScope scope);

    List<EmsAlarmRule> selectMatchedAlarmRules(@Param("tenantId") Long tenantId,
                                               @Param("companyId") Long companyId,
                                               @Param("stationId") Long stationId,
                                               @Param("deviceType") String deviceType,
                                               @Param("metricKey") String metricKey,
                                               @Param("severity") String severity);
}
