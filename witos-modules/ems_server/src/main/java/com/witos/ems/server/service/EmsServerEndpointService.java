package com.witos.ems.server.service;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.Map;

public interface EmsServerEndpointService
{
    IPage<Map<String, Object>> list(Map<String, String> query);

    List<Map<String, Object>> listEnabledOptions();

    Map<String, Object> get(Long id);

    Map<String, Object> save(Map<String, Object> body);

    Map<String, Object> changeStatus(Long id, String enabled);

    boolean remove(Long id);

    Map<String, Object> test(Long id);
}