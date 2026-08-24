package com.witos.ems.server.service;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.Map;

public interface EmsCompanyService
{
    IPage<Map<String, Object>> list(Map<String, String> query);

    List<Map<String, Object>> listAll(Map<String, String> query);

    List<Map<String, Object>> tree();

    Map<String, Object> get(Long id);

    Map<String, Object> save(Map<String, Object> body);

    boolean remove(Long id);
}
