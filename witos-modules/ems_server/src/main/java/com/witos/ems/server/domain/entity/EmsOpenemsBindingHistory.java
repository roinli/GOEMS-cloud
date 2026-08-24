package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_openems_binding_history")
public class EmsOpenemsBindingHistory extends TenantEntity
{
    @TableId
    private Long id;

    private Long bindingId;

    private String resourceType;

    private Long resourceId;

    private Long endpointId;

    private String edgeId;

    private String componentId;

    private Long oldCompanyId;

    private Long oldStationId;

    private Long newCompanyId;

    private Long newStationId;

    private Date effectiveFrom;

    private Date effectiveTo;

    private String operationType;

    private String operationBy;

    private String reason;

    private String rebuildTaskNo;

    private String rawJson;
}
