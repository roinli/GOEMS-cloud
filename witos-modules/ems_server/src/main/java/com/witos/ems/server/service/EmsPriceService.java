package com.witos.ems.server.service;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.Map;

public interface EmsPriceService
{
    IPage<Map<String, Object>> list(Map<String, String> query);

    List<Map<String, Object>> listAll(Map<String, String> query);

    Map<String, Object> get(Long id);

    Map<String, Object> save(Map<String, Object> body);

    Map<String, Object> changeStatus(Long id, String status);

    Map<String, Object> setDefault(Long id);

    void initDefaultsForCompany(Long tenantId, Long companyId);

    void initDefaultAppliesForStation(Long tenantId, Long companyId, Long stationId);

    List<Map<String, Object>> listBindableRules(Long stationId, String priceType);

    List<Map<String, Object>> listStationBindings(Long stationId);

    Map<String, Object> saveStationBinding(Map<String, Object> body);

    boolean removeStationBinding(Long applyId);

    boolean remove(Long id);
}
