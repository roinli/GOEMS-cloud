package com.witos.ems.server.service;

import java.util.List;
import java.util.Map;

public interface EmsEmployeeRoleService
{
    List<Map<String, Object>> listAssignableRoles(Long tenantId);

    List<Long> listUserRoleIds(Long tenantId, Long userId);

    List<String> listUserRoleNames(Long tenantId, Long userId);

    List<String> listUserRoleKeys(Long tenantId, Long userId);

    void replaceUserRoles(Long tenantId, Long userId, List<Long> roleIds);

    void removeUserRoles(Long tenantId, Long userId);
}
