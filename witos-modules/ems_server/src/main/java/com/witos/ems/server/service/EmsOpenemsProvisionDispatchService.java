package com.witos.ems.server.service;

public interface EmsOpenemsProvisionDispatchService
{
    int dispatchPendingCurrentTenant(Long endpointId, String edgeId);
}
