package com.witos.ems.server.service;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.Map;

public interface EmsAlarmService
{
    List<Map<String, Object>> current(Map<String, String> query);

    IPage<Map<String, Object>> history(Map<String, String> query);

    List<Map<String, Object>> historyList(Map<String, String> query);

    IPage<Map<String, Object>> rules(Map<String, String> query);

    Map<String, Object> getRule(Long id);

    Map<String, Object> saveRule(Map<String, Object> body);

    IPage<Map<String, Object>> mailConfigs(Map<String, String> query);

    Map<String, Object> getMailConfig(Long id);

    Map<String, Object> saveMailConfig(Map<String, Object> body);

    boolean removeMailConfig(Long id);

    IPage<Map<String, Object>> notifyLogs(Map<String, String> query);

    List<Map<String, Object>> notifyLogList(Map<String, String> query);

    boolean removeRule(Long id);

    boolean ack(Long id);

    boolean clear(Long id);

    Map<String, Object> report(Map<String, Object> body);

    int cleanupHistory();
}
