package com.witos.ems.server.service;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.Map;

public interface EmsOpenemsEdgeService
{
    IPage<Map<String, Object>> list(Map<String, String> query);

    Map<String, Object> get(Long id);

    Map<String, Object> create(Map<String, Object> body);

    Map<String, Object> getCreateTask(String requestNo);

    Map<String, Object> revealCredentials(Long id);
}
