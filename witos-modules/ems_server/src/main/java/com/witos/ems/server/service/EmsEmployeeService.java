package com.witos.ems.server.service;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.Map;

public interface EmsEmployeeService
{
    IPage<Map<String, Object>> list(Map<String, String> query);

    List<Map<String, Object>> listAll(Map<String, String> query);

    Map<String, Object> get(Long userId);

    List<Map<String, Object>> roleOptions();

    Map<String, Object> save(Map<String, Object> body);

    boolean remove(Long userId);

    List<Map<String, Object>> deptTree(Long companyId);
}
