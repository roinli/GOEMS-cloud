package com.witos.ems.server.auth;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class EmsDataScope
{
    private Long userId;

    private boolean platformFullAccess;

    private boolean tenantFullAccess;

    private List<Long> companyIds = new ArrayList<Long>();

    private List<Long> stationIds = new ArrayList<Long>();

    public boolean isScopeRestricted()
    {
        return !platformFullAccess && !tenantFullAccess;
    }
}
