package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_device_binding_history")
public class EmsDeviceBindingHistory extends TenantEntity
{
    @TableId
    private Long id;

    private Long companyId;

    private Long stationId;

    private Long deviceId;

    private Long deviceComponentId;

    private Long serverEndpointId;

    private String edgeId;

    private String componentId;

    private String serialNo;

    private String parentEdgeId;

    private String parentComponentId;

    private String componentType;

    private String componentAlias;

    private Date bindTime;

    private Date unbindTime;

    private String bindStatus;

    private String bindBy;

    private String unbindBy;
}