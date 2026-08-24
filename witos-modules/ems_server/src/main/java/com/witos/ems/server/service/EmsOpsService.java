package com.witos.ems.server.service;

import java.util.Map;

public interface EmsOpsService
{
    Map<String, Object> status(Map<String, String> query);
}
