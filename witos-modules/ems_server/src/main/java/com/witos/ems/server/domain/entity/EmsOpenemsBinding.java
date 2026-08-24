package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_openems_binding")
public class EmsOpenemsBinding extends TenantEntity
{
    @TableId
    private Long id;

    private String resourceType;

    private Long resourceId;

    private Long endpointId;

    private String edgeId;

    private String componentId;

    private Long companyId;

    private Long stationId;

    private Date effectiveFrom;

    private Date effectiveTo;

    private String status;

    private String source;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
