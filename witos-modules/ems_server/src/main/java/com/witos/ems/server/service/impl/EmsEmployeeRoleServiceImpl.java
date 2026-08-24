package com.witos.ems.server.service.impl;

import com.witos.common.core.constant.SecurityConstants;
import com.witos.common.core.domain.R;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.service.EmsEmployeeRoleService;
import com.witos.ems.server.support.EmsRequestSupport;
import com.witos.system.api.RemoteUserService;
import com.witos.system.api.domain.EmsUserRolePayload;
import com.witos.system.api.domain.SysRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class EmsEmployeeRoleServiceImpl implements EmsEmployeeRoleService
{
    private static final String EMS_ROLE_PREFIX = "ems_";
    private static final String INSTALLER_ADMIN_ROLE_KEY = "ems_installer_admin";

    @Resource
    private RemoteUserService remoteUserService;

    @Resource
    private EmsAuthScopeService authScopeService;

    @Override
    public List<Map<String, Object>> listAssignableRoles(Long tenantId)
    {
        boolean platformFullAccess = authScopeService.currentScope().isPlatformFullAccess();
        List<SysRole> roles = requireRemote(remoteUserService.listEmsRoles(tenantId, SecurityConstants.INNER));

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (SysRole role : roles)
        {
            if (role == null || StringUtils.isEmpty(role.getRoleKey()) || !role.getRoleKey().startsWith(EMS_ROLE_PREFIX))
            {
                continue;
            }
            if (!platformFullAccess && INSTALLER_ADMIN_ROLE_KEY.equals(role.getRoleKey()))
            {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("roleId", role.getRoleId());
            row.put("roleName", role.getRoleName());
            row.put("roleKey", role.getRoleKey());
            row.put("remark", role.getRemark());
            rows.add(row);
        }
        return rows;
    }

    @Override
    public List<Long> listUserRoleIds(Long tenantId, Long userId)
    {
        return selectUserRoles(tenantId, userId)
                .stream()
                .map(SysRole::getRoleId)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> listUserRoleNames(Long tenantId, Long userId)
    {
        List<String> names = new ArrayList<String>();
        for (SysRole role : selectUserRoles(tenantId, userId))
        {
            if (StringUtils.isNotEmpty(role.getRoleName()))
            {
                names.add(role.getRoleName());
            }
        }
        return names;
    }

    @Override
    public List<String> listUserRoleKeys(Long tenantId, Long userId)
    {
        List<String> keys = new ArrayList<String>();
        for (SysRole role : selectUserRoles(tenantId, userId))
        {
            if (StringUtils.isNotEmpty(role.getRoleKey()))
            {
                keys.add(role.getRoleKey());
            }
        }
        return keys;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceUserRoles(Long tenantId, Long userId, List<Long> roleIds)
    {
        validateAssignableRoles(tenantId, roleIds);
        EmsUserRolePayload payload = new EmsUserRolePayload();
        payload.setTenantId(tenantId);
        payload.setUserId(userId);
        payload.setRoleIds(roleIds);
        requireRemote(remoteUserService.replaceEmsUserRoles(payload, SecurityConstants.INNER));
    }

    @Override
    public void removeUserRoles(Long tenantId, Long userId)
    {
        EmsUserRolePayload payload = new EmsUserRolePayload();
        payload.setTenantId(tenantId);
        payload.setUserId(userId);
        payload.setRoleIds(new ArrayList<Long>());
        requireRemote(remoteUserService.replaceEmsUserRoles(payload, SecurityConstants.INNER));
    }

    private void validateAssignableRoles(Long tenantId, List<Long> roleIds)
    {
        if (roleIds == null || roleIds.isEmpty())
        {
            throw new ServiceException("至少选择一个系统角色");
        }

        Set<Long> distinctRoleIds = new LinkedHashSet<Long>(roleIds);
        List<SysRole> roles = requireRemote(remoteUserService.listEmsRoles(tenantId, SecurityConstants.INNER)).stream()
                .filter(role -> role != null && role.getRoleId() != null && distinctRoleIds.contains(role.getRoleId()))
                .filter(role -> StringUtils.isNotEmpty(role.getRoleKey()) && role.getRoleKey().startsWith(EMS_ROLE_PREFIX))
                .collect(Collectors.toList());

        if (roles.size() != distinctRoleIds.size())
        {
            throw new ServiceException("存在非法角色或角色不属于当前平台范围");
        }

        boolean platformFullAccess = authScopeService.currentScope().isPlatformFullAccess();
        Long currentTenantId = EmsRequestSupport.currentTenantId();
        for (SysRole role : roles)
        {
            if (!platformFullAccess && INSTALLER_ADMIN_ROLE_KEY.equals(role.getRoleKey()))
            {
                throw new ServiceException("默认安装商管理员角色不能通过员工页分配");
            }
            if (!tenantId.equals(role.getTenantId()))
            {
                throw new ServiceException("不能选择其他租户的角色");
            }
            if (!platformFullAccess && !currentTenantId.equals(tenantId))
            {
                throw new ServiceException("不能选择其他租户的角色");
            }
        }
    }

    private List<SysRole> selectUserRoles(Long tenantId, Long userId)
    {
        return requireRemote(remoteUserService.listEmsUserRoles(userId, tenantId, SecurityConstants.INNER))
                .stream()
                .filter(SysRole::isFlag)
                .filter(role -> StringUtils.isNotEmpty(role.getRoleKey()) && role.getRoleKey().startsWith(EMS_ROLE_PREFIX))
                .collect(Collectors.toList());
    }

    private <T> T requireRemote(R<T> result)
    {
        if (result == null || R.isError(result))
        {
            throw new ServiceException(result == null || StringUtils.isEmpty(result.getMsg()) ? "系统服务调用失败" : result.getMsg());
        }
        return result.getData();
    }
}
