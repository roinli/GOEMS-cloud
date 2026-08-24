package com.witos.ems.server.auth;

import com.witos.common.security.utils.SecurityUtils;
import com.witos.ems.server.mapper.EmsAuthScopeMapper;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class EmsAuthScopeService
{
    private static final String INSTALLER_ADMIN_ROLE_KEY = "ems_installer_admin";

    @Resource
    private EmsAuthScopeMapper authScopeMapper;

    public EmsDataScope currentScope()
    {
        Long userId = SecurityUtils.getUserId();

        EmsDataScope scope = new EmsDataScope();
        scope.setUserId(userId);

        if (SecurityUtils.isSuperAdmin())
        {
            scope.setPlatformFullAccess(true);
            return scope;
        }

        Map<String, Object> profile = authScopeMapper.selectUserProfile(userId);
        if (profile == null || profile.isEmpty())
        {
            return scope;
        }

        String defaultInstallerAdmin = EmsRequestSupport.defaultString(profile.get("isDefaultInstallerAdmin"), "0");
        List<String> roleKeys = authScopeMapper.selectUserRoleKeys(userId);
        if ("1".equals(defaultInstallerAdmin) && roleKeys.contains(INSTALLER_ADMIN_ROLE_KEY))
        {
            scope.setTenantFullAccess(true);
            return scope;
        }

        List<Map<String, Object>> scopes = authScopeMapper.selectUserScopes(userId);
        List<Map<String, Object>> companyTree = authScopeMapper.selectCompanyTree();

        Set<Long> companyIds = new LinkedHashSet<Long>();
        Set<Long> stationIds = new LinkedHashSet<Long>();

        for (Map<String, Object> row : scopes)
        {
            String scopeType = EmsRequestSupport.stringValue(row.get("scopeType"));
            if ("COMPANY".equalsIgnoreCase(scopeType))
            {
                Long companyId = EmsRequestSupport.asLong(row.get("companyId"));
                if (companyId != null)
                {
                    expandCompanyScope(companyIds, companyTree, companyId);
                }
            }
            else if ("STATION".equalsIgnoreCase(scopeType))
            {
                Long stationId = EmsRequestSupport.asLong(row.get("stationId"));
                if (stationId != null)
                {
                    stationIds.add(stationId);
                }
            }
        }

        if (!stationIds.isEmpty())
        {
            companyIds.addAll(authScopeMapper.selectCompanyIdsByStationIds(new ArrayList<Long>(stationIds)));
        }

        scope.setCompanyIds(new ArrayList<Long>(companyIds));
        scope.setStationIds(new ArrayList<Long>(stationIds));
        return scope;
    }

    private void expandCompanyScope(Set<Long> companyIds, List<Map<String, Object>> companyTree, Long rootCompanyId)
    {
        companyIds.add(rootCompanyId);
        String token = String.valueOf(rootCompanyId);
        for (Map<String, Object> company : companyTree)
        {
            Long companyId = EmsRequestSupport.asLong(company.get("id"));
            String ancestors = EmsRequestSupport.stringValue(company.get("ancestors"));
            if (companyId != null && containsAncestor(ancestors, token))
            {
                companyIds.add(companyId);
            }
        }
    }

    private boolean containsAncestor(String ancestors, String token)
    {
        if (ancestors == null || ancestors.isEmpty())
        {
            return false;
        }
        String[] parts = ancestors.split(",");
        for (String part : parts)
        {
            if (token.equals(part))
            {
                return true;
            }
        }
        return false;
    }
}
