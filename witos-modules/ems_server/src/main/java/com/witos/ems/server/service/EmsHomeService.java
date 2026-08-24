package com.witos.ems.server.service;

import java.util.Map;

public interface EmsHomeService
{
    Map<String, Object> buildView(String variant, Map<String, String> query);
}
