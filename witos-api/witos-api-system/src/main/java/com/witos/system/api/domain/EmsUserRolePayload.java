package com.witos.system.api.domain;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class EmsUserRolePayload
{
    private Long tenantId;

    private Long userId;

    private List<Long> roleIds = new ArrayList<Long>();
}
