package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.domain.entity.EmsBusinessParam;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsBusinessParamMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.ems.server.service.EmsBusinessConfigService;
import com.witos.ems.server.support.EmsBusinessParamTemplate;
import com.witos.ems.server.support.EmsPageSupport;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EmsBusinessConfigServiceImpl implements EmsBusinessConfigService
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private EmsBusinessParamMapper businessParamMapper;

    @Resource
    private EmsCompanyMapper companyMapper;

    @Resource
    private EmsStationMapper stationMapper;

    @Resource
    private EmsAuthScopeService authScopeService;

    @Resource
    private EmsBusinessParamResolver businessParamResolver;

    @Override
    public IPage<Map<String, Object>> list(Map<String, String> query)
    {
        IPage<Map<String, Object>> page = businessParamMapper.selectBusinessConfigPage(EmsPageSupport.page(), queryMap(query), authScopeService.currentScope());
        page.getRecords().replaceAll(this::enrichRow);
        return page;
    }

    @Override
    public List<Map<String, Object>> listAll(Map<String, String> query)
    {
        List<Map<String, Object>> rows = businessParamMapper.selectBusinessConfigList(queryMap(query), authScopeService.currentScope());
        rows.replaceAll(this::enrichRow);
        return rows;
    }

    @Override
    public Map<String, Object> get(Long id)
    {
        Map<String, Object> detail = businessParamMapper.selectBusinessConfigDetail(id, authScopeService.currentScope());
        return detail == null ? new LinkedHashMap<String, Object>() : enrichRow(detail);
    }

    @Override
    public List<Map<String, Object>> templates()
    {
        return EmsBusinessParamTemplate.optionList();
    }

    @Override
    public Map<String, Object> coreValues(Map<String, String> query)
    {
        Long companyId = EmsRequestSupport.asLong(query == null ? null : query.get("companyId"));
        Long stationId = EmsRequestSupport.asLong(query == null ? null : query.get("stationId"));
        Long tenantId = resolveScopeTenant(companyId, stationId);
        return businessParamResolver.resolveCoreValues(tenantId, companyId, stationId);
    }

    @Override
    public Map<String, Object> save(Map<String, Object> body)
    {
        Long id = EmsRequestSupport.coalesceId(body, "id");
        EmsBusinessParam current = null;
        if (id != null)
        {
            Map<String, Object> detail = businessParamMapper.selectBusinessConfigDetail(id, authScopeService.currentScope());
            if (detail == null || detail.isEmpty())
            {
                throw new ServiceException("业务参数不存在或无权修改");
            }
            current = businessParamMapper.selectById(id);
        }
        String paramKey = EmsRequestSupport.stringValue(body.get("paramKey"));
        if (StringUtils.isEmpty(paramKey))
        {
            throw new ServiceException("参数键不能为空");
        }
        EmsBusinessParamTemplate template = EmsBusinessParamTemplate.fromKey(paramKey);
        if (template == null)
        {
            throw new ServiceException("不支持的业务参数，仅允许配置固定的碳排折算参数");
        }
        String scopeType = EmsRequestSupport.defaultString(body.get("scopeType"), "TENANT").toUpperCase();
        if (!"TENANT".equals(scopeType) && !"COMPANY".equals(scopeType) && !"STATION".equals(scopeType))
        {
            throw new ServiceException("作用域不合法");
        }
        String valueType = template.getValueType();
        Long companyId = normalizeCompanyId(scopeType, EmsRequestSupport.asLong(body.get("companyId")));
        Long stationId = normalizeStationId(scopeType, EmsRequestSupport.asLong(body.get("stationId")));

        Long tenantId = "TENANT".equals(scopeType)
                ? current == null ? EmsRequestSupport.requestedTenantId(body) : current.getTenantId()
                : resolveScopeTenant(companyId, stationId);
        if (current != null && !java.util.Objects.equals(current.getTenantId(), tenantId))
        {
            throw new ServiceException("不能将业务参数迁移到其他租户");
        }
        validateTemplateAndType(paramKey, valueType, scopeType, body.get("paramValue"));
        validateDuplicate(tenantId, id, paramKey, scopeType, companyId, stationId);

        EmsBusinessParam param = new EmsBusinessParam();
        param.setId(id);
        param.setTenantId(tenantId);
        param.setCompanyId(companyId);
        param.setStationId(stationId);
        param.setParamKey(paramKey);
        param.setParamValue(EmsRequestSupport.stringValue(body.get("paramValue")));
        param.setValueType(valueType);
        param.setScopeType(scopeType);
        param.setStatus(EmsRequestSupport.defaultString(body.get("status"), "0"));
        param.setRemark(EmsRequestSupport.stringValue(body.get("remark")));

        if (id == null)
        {
            businessParamMapper.insert(param);
            id = param.getId();
        }
        else
        {
            businessParamMapper.updateById(param);
        }
        return get(id);
    }

    @Override
    public boolean remove(Long id)
    {
        Map<String, Object> detail = businessParamMapper.selectBusinessConfigDetail(id, authScopeService.currentScope());
        if (detail == null || detail.isEmpty())
        {
            return false;
        }
        return businessParamMapper.deleteById(id) > 0;
    }

    @Override
    public void bindCompanyDefaults(Long tenantId, Long companyId)
    {
        if (tenantId == null || companyId == null || companyId <= 0)
        {
            return;
        }
        for (EmsBusinessParamTemplate template : EmsBusinessParamTemplate.values())
        {
            EmsBusinessParam existing = businessParamMapper.selectOne(new LambdaQueryWrapper<EmsBusinessParam>()
                    .eq(EmsBusinessParam::getTenantId, tenantId)
                    .eq(EmsBusinessParam::getParamKey, template.getKey())
                    .eq(EmsBusinessParam::getScopeType, "COMPANY")
                    .eq(EmsBusinessParam::getCompanyId, companyId)
                    .eq(EmsBusinessParam::getStationId, 0L)
                    .eq(EmsBusinessParam::getDelFlag, "0")
                    .last("limit 1"));
            if (existing != null)
            {
                continue;
            }
            EmsBusinessParam param = new EmsBusinessParam();
            param.setTenantId(tenantId);
            param.setCompanyId(companyId);
            param.setStationId(0L);
            param.setParamKey(template.getKey());
            param.setParamValue(template.getDefaultValue());
            param.setValueType(template.getValueType());
            param.setScopeType("COMPANY");
            param.setStatus("0");
            businessParamMapper.insert(param);
        }
    }

    private Map<String, Object> queryMap(Map<String, String> query)
    {
        return query == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(query);
    }

    private Map<String, Object> enrichRow(Map<String, Object> row)
    {
        if (row == null)
        {
            return new LinkedHashMap<String, Object>();
        }
        EmsBusinessParamTemplate template = EmsBusinessParamTemplate.fromKey(EmsRequestSupport.stringValue(row.get("paramKey")));
        row.put("coreParam", template != null);
        row.put("templateName", template == null ? row.get("paramKey") : template.getLabel());
        row.put("defaultValue", template == null ? "" : template.getDefaultValue());
        row.put("unit", template == null ? "" : template.getUnit());
        row.put("description", template == null ? "" : template.getDescription());
        row.put("scopeTypes", template == null ? java.util.Collections.emptyList() : template.getScopeTypes());
        return row;
    }

    private Long normalizeCompanyId(String scopeType, Long companyId)
    {
        if ("TENANT".equals(scopeType))
        {
            return 0L;
        }
        if (companyId == null || companyId <= 0)
        {
            throw new ServiceException("公司不能为空");
        }
        return companyId;
    }

    private Long normalizeStationId(String scopeType, Long stationId)
    {
        if ("STATION".equals(scopeType))
        {
            if (stationId == null || stationId <= 0)
            {
                throw new ServiceException("电站不能为空");
            }
            return stationId;
        }
        return 0L;
    }

    private Long resolveScopeTenant(Long companyId, Long stationId)
    {
        Map<String, Object> stationDetail = null;
        if (stationId != null && stationId > 0)
        {
            stationDetail = stationMapper.selectStationDetail(stationId, authScopeService.currentScope());
            if (stationDetail == null || stationDetail.isEmpty())
            {
                throw new ServiceException("电站超出当前授权范围");
            }
            Long stationCompanyId = EmsRequestSupport.asLong(stationDetail.get("companyId"));
            if (companyId == null)
            {
                companyId = stationCompanyId;
            }
            else if (stationCompanyId != null && !stationCompanyId.equals(companyId))
            {
                throw new ServiceException("电站与公司不匹配");
            }
        }
        if (companyId == null || companyId <= 0)
        {
            return EmsRequestSupport.currentTenantId();
        }
        Map<String, Object> companyDetail = companyMapper.selectCompanyDetail(companyId, authScopeService.currentScope());
        if (companyDetail == null || companyDetail.isEmpty())
        {
            throw new ServiceException("公司超出当前授权范围");
        }
        Long tenantId = EmsRequestSupport.asLong(companyDetail.get("tenantId"));
        if (stationDetail != null)
        {
            Long stationTenantId = EmsRequestSupport.asLong(stationDetail.get("tenantId"));
            if (!java.util.Objects.equals(tenantId, stationTenantId))
            {
                throw new ServiceException("电站与公司不属于同一租户");
            }
        }
        return tenantId;
    }

    private void validateTemplateAndType(String paramKey, String valueType, String scopeType, Object value)
    {
        EmsBusinessParamTemplate template = EmsBusinessParamTemplate.fromKey(paramKey);
        if (template == null)
        {
            throw new ServiceException("不支持的业务参数，仅允许配置固定的碳排折算参数");
        }
        if (!template.supportsScope(scopeType))
        {
            throw new ServiceException("核心参数不支持当前作用域");
        }
        if (!template.getValueType().equalsIgnoreCase(valueType))
        {
            throw new ServiceException("核心参数值类型必须为 " + template.getValueType());
        }
        validateValue(valueType, value);
    }

    private void validateDuplicate(Long tenantId, Long id, String paramKey, String scopeType,
                                   Long companyId, Long stationId)
    {
        EmsBusinessParam duplicate = businessParamMapper.selectOne(new LambdaQueryWrapper<EmsBusinessParam>()
                .eq(EmsBusinessParam::getTenantId, tenantId)
                .eq(EmsBusinessParam::getParamKey, paramKey)
                .eq(EmsBusinessParam::getScopeType, scopeType)
                .eq(EmsBusinessParam::getCompanyId, companyId == null ? 0L : companyId)
                .eq(EmsBusinessParam::getStationId, stationId == null ? 0L : stationId)
                .eq(EmsBusinessParam::getDelFlag, "0")
                .ne(id != null, EmsBusinessParam::getId, id)
                .last("limit 1"));
        if (duplicate != null)
        {
            throw new ServiceException("同一作用域下已存在相同参数键，请直接编辑现有配置");
        }
    }

    private void validateValue(String valueType, Object value)
    {
        String text = EmsRequestSupport.stringValue(value).trim();
        if (StringUtils.isEmpty(text))
        {
            throw new ServiceException("参数值不能为空");
        }
        try
        {
            if ("NUMBER".equalsIgnoreCase(valueType))
            {
                new BigDecimal(text);
                return;
            }
            if ("BOOLEAN".equalsIgnoreCase(valueType))
            {
                if (!"true".equalsIgnoreCase(text)
                        && !"false".equalsIgnoreCase(text)
                        && !"1".equals(text)
                        && !"0".equals(text))
                {
                    throw new ServiceException("布尔类型仅支持 true/false/1/0");
                }
                return;
            }
            if ("JSON".equalsIgnoreCase(valueType))
            {
                OBJECT_MAPPER.readTree(text);
            }
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new ServiceException("参数值与值类型不匹配");
        }
    }
}
