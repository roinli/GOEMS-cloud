package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

@Data
@TableName("ems_user_scope")
public class EmsUserScope extends TenantEntity
{
    @TableId
    private Long id;

    private Long userId;

    private String scopeType;

    private Long companyId;

    private Long stationId;

    private String includeDescendants;
}
