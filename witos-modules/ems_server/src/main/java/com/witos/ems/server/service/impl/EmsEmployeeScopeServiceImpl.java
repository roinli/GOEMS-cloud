package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.common.core.exception.ServiceException;
import com.witos.ems.server.domain.entity.EmsUserScope;
import com.witos.ems.server.mapper.EmsUserScopeMapper;
import com.witos.ems.server.service.EmsEmployeeScopeService;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class EmsEmployeeScopeServiceImpl implements EmsEmployeeScopeService
{
    @Resource
    private EmsUserScopeMapper userScopeMapper;

    @Override
    public void fillScopeDetail(Map<String, Object> detail, Long userId)
    {
        List<EmsUserScope> scopes = userScopeMapper.selectList(new LambdaQueryWrapper<EmsUserScope>()
                .eq(EmsUserScope::getUserId, userId)
                .orderByAsc(EmsUserScope::getId));

        List<Long> companyIds = new ArrayList<Long>();
        List<Long> stationIds = new ArrayList<Long>();
        String scopeMode = "COMPANY";

        for (EmsUserScope scope : scopes)
        {
            if ("STATION".equalsIgnoreCase(scope.getScopeType()))
            {
                scopeMode = "STATION";
                if (scope.getStationId() != null && scope.getStationId() > 0)
                {
                    stationIds.add(scope.getStationId());
                }
            }
            else if (scope.getCompanyId() != null && scope.getCompanyId() > 0)
            {
                companyIds.add(scope.getCompanyId());
            }
        }

        detail.put("scopeMode", scopeMode);
        detail.put("scopeCompanyIds", companyIds);
        detail.put("scopeStationIds", stationIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceUserScopes(Long tenantId, Long userId, Long companyId, Map<String, Object> body)
    {
        removeUserScopes(tenantId, userId);

        String scopeMode = EmsRequestSupport.defaultString(body.get("scopeMode"), "COMPANY");
        if ("STATION".equalsIgnoreCase(scopeMode))
        {
            List<Long> stationIds = EmsRequestSupport.asLongList(body.get("scopeStationIds"));
            Long stationId = EmsRequestSupport.asLong(body.get("stationId"));
            if (stationIds.isEmpty() && stationId != null)
            {
                stationIds.add(stationId);
            }
            if (stationIds.isEmpty())
            {
                throw new ServiceException("电站授权范围不能为空");
            }
            for (Long item : stationIds)
            {
                EmsUserScope scope = new EmsUserScope();
                scope.setTenantId(tenantId);
                scope.setUserId(userId);
                scope.setScopeType("STATION");
                scope.setCompanyId(0L);
                scope.setStationId(item);
                scope.setIncludeDescendants("1");
                scope.setCreateBy(EmsRequestSupport.currentUsername());
                userScopeMapper.insert(scope);
            }
            return;
        }

        List<Long> companyIds = EmsRequestSupport.asLongList(body.get("scopeCompanyIds"));
        if (companyIds.isEmpty())
        {
            companyIds.add(companyId);
        }
        for (Long item : companyIds)
        {
            EmsUserScope scope = new EmsUserScope();
            scope.setTenantId(tenantId);
            scope.setUserId(userId);
            scope.setScopeType("COMPANY");
            scope.setCompanyId(item);
            scope.setStationId(0L);
            scope.setIncludeDescendants("0");
            scope.setCreateBy(EmsRequestSupport.currentUsername());
            userScopeMapper.insert(scope);
        }
    }

    @Override
    public void removeUserScopes(Long tenantId, Long userId)
    {
        userScopeMapper.delete(new LambdaQueryWrapper<EmsUserScope>()
                .eq(EmsUserScope::getTenantId, tenantId)
                .eq(EmsUserScope::getUserId, userId));
    }
}
