package com.witos.ems.server.service;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.Map;

public interface EmsBusinessConfigService
{
    IPage<Map<String, Object>> list(Map<String, String> query);

    List<Map<String, Object>> listAll(Map<String, String> query);

    Map<String, Object> get(Long id);

    List<Map<String, Object>> templates();

    Map<String, Object> coreValues(Map<String, String> query);

    Map<String, Object> save(Map<String, Object> body);

    boolean remove(Long id);

    void bindCompanyDefaults(Long tenantId, Long companyId);
}
