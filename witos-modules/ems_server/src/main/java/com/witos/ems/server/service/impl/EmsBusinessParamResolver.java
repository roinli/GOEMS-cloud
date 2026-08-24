package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.domain.entity.EmsBusinessParam;
import com.witos.ems.server.mapper.EmsBusinessParamMapper;
import com.witos.ems.server.support.EmsBusinessParamTemplate;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class EmsBusinessParamResolver
{
    @Resource
    private EmsBusinessParamMapper businessParamMapper;

    public Map<String, Object> resolveCoreValues(Long companyId, Long stationId)
    {
        return resolveCoreValues(EmsRequestSupport.currentTenantId(), companyId, stationId);
    }

    public Map<String, Object> resolveCoreValues(Long tenantId, Long companyId, Long stationId)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (EmsBusinessParamTemplate template : EmsBusinessParamTemplate.values())
        {
            String value = resolve(template, tenantId, companyId, stationId);
            result.put(template.getKey(), value);
        }
        return result;
    }

    public String resolve(EmsBusinessParamTemplate template, Long companyId, Long stationId)
    {
        Long tenantId = EmsRequestSupport.currentTenantId();
        return resolve(template, tenantId, companyId, stationId);
    }

    public String resolve(EmsBusinessParamTemplate template, Long tenantId, Long companyId, Long stationId)
    {
        if (stationId != null && stationId > 0)
        {
            EmsBusinessParam stationParam = findActiveParam(tenantId, template.getKey(), "STATION", companyId, stationId);
            if (stationParam != null && StringUtils.isNotEmpty(stationParam.getParamValue()))
            {
                return stationParam.getParamValue();
            }
        }
        if (companyId != null && companyId > 0)
        {
            EmsBusinessParam companyParam = findActiveParam(tenantId, template.getKey(), "COMPANY", companyId, 0L);
            if (companyParam != null && StringUtils.isNotEmpty(companyParam.getParamValue()))
            {
                return companyParam.getParamValue();
            }
        }
        EmsBusinessParam tenantParam = findActiveParam(tenantId, template.getKey(), "TENANT", 0L, 0L);
        if (tenantParam != null && StringUtils.isNotEmpty(tenantParam.getParamValue()))
        {
            return tenantParam.getParamValue();
        }
        return template.getDefaultValue();
    }

    public BigDecimal resolveDecimal(EmsBusinessParamTemplate template, Long companyId, Long stationId)
    {
        return new BigDecimal(resolve(template, companyId, stationId));
    }

    public BigDecimal resolveDecimal(EmsBusinessParamTemplate template, Long tenantId, Long companyId, Long stationId)
    {
        return new BigDecimal(resolve(template, tenantId, companyId, stationId));
    }

    public Integer resolveInteger(EmsBusinessParamTemplate template, Long companyId, Long stationId)
    {
        return Integer.parseInt(resolve(template, companyId, stationId));
    }

    public Integer resolveInteger(EmsBusinessParamTemplate template, Long tenantId, Long companyId, Long stationId)
    {
        return Integer.parseInt(resolve(template, tenantId, companyId, stationId));
    }

    private EmsBusinessParam findActiveParam(Long tenantId, String paramKey, String scopeType, Long companyId, Long stationId)
    {
        return businessParamMapper.selectOne(new LambdaQueryWrapper<EmsBusinessParam>()
                .eq(EmsBusinessParam::getTenantId, tenantId)
                .eq(EmsBusinessParam::getParamKey, paramKey)
                .eq(EmsBusinessParam::getScopeType, scopeType)
                .eq(EmsBusinessParam::getCompanyId, companyId == null ? 0L : companyId)
                .eq(EmsBusinessParam::getStationId, stationId == null ? 0L : stationId)
                .eq(EmsBusinessParam::getStatus, "0")
                .orderByDesc(EmsBusinessParam::getId)
                .last("limit 1"));
    }
}
