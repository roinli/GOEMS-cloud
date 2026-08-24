package com.witos.ems.server.service;

import java.util.Map;

public interface EmsReportSyncTaskService
{
    Map<String, Object> startTask(Map<String, Object> body);

    Map<String, Object> retryTask(Long id);

    Map<String, Object> listTasks(Map<String, String> query);
}
