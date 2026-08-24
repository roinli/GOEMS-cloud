package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.domain.entity.EmsCompany;
import com.witos.ems.server.domain.entity.EmsStation;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.ems.server.service.EmsPriceService;
import com.witos.ems.server.service.EmsStationService;
import com.witos.ems.server.support.EmsPageSupport;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EmsStationServiceImpl implements EmsStationService
{
    @Resource
    private EmsStationMapper stationMapper;

    @Resource
    private EmsCompanyMapper companyMapper;

    @Resource
    private EmsAuthScopeService authScopeService;

    @Resource
    private EmsPriceService priceService;

    @Override
    public IPage<Map<String, Object>> list(Map<String, String> query)
    {
        return stationMapper.selectStationPage(EmsPageSupport.page(), queryMap(query), authScopeService.currentScope());
    }

    @Override
    public List<Map<String, Object>> listAll(Map<String, String> query)
    {
        return stationMapper.selectStationList(queryMap(query), authScopeService.currentScope());
    }

    @Override
    public Map<String, Object> get(Long id)
    {
        return stationMapper.selectStationDetail(id, authScopeService.currentScope());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Map<String, Object> body)
    {
        Long stationId = EmsRequestSupport.coalesceId(body, "stationId", "id");
        boolean create = stationId == null;
        Long companyId = EmsRequestSupport.requiredLong(body, "companyId", "公司不能为空");
        String stationName = EmsRequestSupport.stringValue(body.get("stationName"));
        if (StringUtils.isEmpty(stationName))
        {
            throw new ServiceException("电站名称不能为空");
        }
        EmsCompany company = validateCompany(companyId);
        EmsStation current = null;
        if (!create)
        {
            Map<String, Object> detail = stationMapper.selectStationDetail(stationId, authScopeService.currentScope());
            if (detail == null || detail.isEmpty())
            {
                throw new ServiceException("电站超出当前授权范围或不存在");
            }
            current = stationMapper.selectById(stationId);
            if (current == null)
            {
                throw new ServiceException("电站不存在");
            }
        }
        Long tenantId = current == null ? company.getTenantId() : current.getTenantId();
        if (!tenantId.equals(company.getTenantId()))
        {
            throw new ServiceException("电站与公司不属于同一租户");
        }

        EmsStation station = new EmsStation();
        station.setId(stationId);
        station.setTenantId(tenantId);
        station.setCompanyId(companyId);
        station.setStationCode(EmsRequestSupport.stringValue(body.get("stationCode")));
        station.setStationName(stationName);
        station.setStationType(EmsRequestSupport.defaultString(body.get("stationType"), "OTHER"));
        station.setCountry(EmsRequestSupport.stringValue(body.get("country")));
        station.setContactName(EmsRequestSupport.stringValue(body.get("contactName")));
        station.setContactPhone(EmsRequestSupport.stringValue(body.get("contactPhone")));
        station.setRunMode(EmsRequestSupport.defaultString(body.get("runMode"), "GRID_CONNECTED"));
        station.setTimezone(EmsRequestSupport.stringValue(body.get("timezone")));
        station.setImageUrl(EmsRequestSupport.stringValue(body.get("imageUrl")));
        station.setCapacityKw(EmsRequestSupport.asBigDecimal(body.get("capacityKw")));
        station.setAddress(EmsRequestSupport.stringValue(body.get("address")));
        station.setLongitude(EmsRequestSupport.asBigDecimal(body.get("longitude")));
        station.setLatitude(EmsRequestSupport.asBigDecimal(body.get("latitude")));
        if (body.containsKey("commissionDate"))
        {
            station.setCommissionDate(EmsRequestSupport.nullableTimestamp(body.get("commissionDate")));
        }
        station.setStatus(EmsRequestSupport.defaultString(body.get("status"), "NORMAL"));
        station.setRemark(EmsRequestSupport.stringValue(body.get("remark")));

        if (stationId == null)
        {
            stationMapper.insert(station);
            stationId = station.getId();
        }
        else
        {
            stationMapper.updateById(station);
        }

        if (create)
        {
            priceService.initDefaultAppliesForStation(tenantId, companyId, stationId);
        }
        return get(stationId);
    }

    @Override
    public boolean remove(Long id)
    {
        return stationMapper.deleteById(id) > 0;
    }

    private Map<String, Object> queryMap(Map<String, String> query)
    {
        return query == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(query);
    }

    private EmsCompany validateCompany(Long companyId)
    {
        Map<String, Object> companyDetail = companyMapper.selectCompanyDetail(companyId, authScopeService.currentScope());
        if (companyDetail == null || companyDetail.isEmpty())
        {
            throw new ServiceException("公司超出当前授权范围");
        }
        EmsCompany company = companyMapper.selectById(companyId);
        if (company == null)
        {
            company = new EmsCompany();
            company.setId(companyId);
            Long tenantId = EmsRequestSupport.asLong(companyDetail.get("tenantId"));
            company.setTenantId(tenantId == null ? EmsRequestSupport.currentTenantId() : tenantId);
        }
        return company;
    }
}
