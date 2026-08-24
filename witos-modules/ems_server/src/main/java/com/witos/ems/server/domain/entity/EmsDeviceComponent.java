package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_device_component")
public class EmsDeviceComponent extends TenantEntity
{
    @TableId
    private Long id;

    private Long companyId;

    private Long stationId;

    private Long deviceId;

    private Long serverEndpointId;

    private String edgeId;

    private String componentId;

    private String componentType;

    private String componentAlias;

    private String serialNo;

    private String parentEdgeId;

    private String parentComponentId;

    private Date bindTime;

    private Date unbindTime;

    private String bindStatus;

    private String bindSource;

    private Date lastSampleTime;

    private String enabled;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
