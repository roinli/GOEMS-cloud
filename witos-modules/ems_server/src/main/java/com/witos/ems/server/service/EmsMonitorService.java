package com.witos.ems.server.service;

import java.util.Map;

public interface EmsMonitorService
{
    Map<String, Object> overview(Map<String, String> query);

    Map<String, Object> deviceDetail(Long deviceId);

    Map<String, Object> deviceDetail(Map<String, String> query);

    Map<String, Object> storageDetail(Long deviceId);

    Map<String, Object> storageHistory(Long deviceId, Map<String, String> query);

    Map<String, Object> startEnergyFlowSession(Map<String, Object> body);

    Map<String, Object> history(Map<String, String> query);

    Map<String, Object> stationView(Map<String, String> query);

    Map<String, Object> logicView(Map<String, String> query);

    Map<String, Object> listViewTabs(Map<String, String> query);

    Map<String, Object> saveViewTab(Map<String, Object> body);

    boolean removeViewTab(Long id);

    Map<String, Object> saveStationView(Map<String, Object> body);

    Map<String, Object> saveLogicView(Map<String, Object> body);
}
