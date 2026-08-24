package com.witos.ems.server.service;

import java.util.Map;

public interface EmsEmployeeScopeService
{
    void fillScopeDetail(Map<String, Object> detail, Long userId);

    void replaceUserScopes(Long tenantId, Long userId, Long companyId, Map<String, Object> body);

    void removeUserScopes(Long tenantId, Long userId);
}
