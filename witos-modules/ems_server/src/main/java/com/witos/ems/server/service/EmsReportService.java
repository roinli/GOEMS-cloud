package com.witos.ems.server.service;

import java.util.Map;

public interface EmsReportService
{
    Map<String, Object> buildReport(String type, Map<String, String> query);

    Map<String, Object> buildStationDetail(Long id);

    Map<String, Object> reportLifecycle(String type, Map<String, String> query);
}
