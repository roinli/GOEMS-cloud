package com.witos.ems.server.service;

import java.util.Map;

public interface EmsOpenemsProvisionService
{
    Map<String, Object> preview(Map<String, Object> body);

    Map<String, Object> create(Map<String, Object> body);

    Map<String, Object> getTask(Long id);

    Map<String, Object> retry(Long id, Map<String, Object> body);
}
