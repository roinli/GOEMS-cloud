package com.witos.ems.server.service;

import com.witos.ems.server.domain.entity.EmsOpenemsDevice;

public interface EmsOpenemsBusinessProjectionService
{
    void syncDevice(EmsOpenemsDevice openemsDevice);
}
