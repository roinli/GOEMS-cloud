package com.witos.ems.server.service;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.Map;

public interface EmsDeviceService
{
    IPage<Map<String, Object>> list(Map<String, String> query, String fixedDeviceType);

    List<Map<String, Object>> listAll(Map<String, String> query, String fixedDeviceType);

    Map<String, Object> get(Long id);

    List<Map<String, Object>> bindCandidates(Map<String, String> query);

    Map<String, Object> save(Map<String, Object> body, String fixedDeviceType);

    Map<String, Object> unbind(Long id);

    boolean remove(Long id);
}
