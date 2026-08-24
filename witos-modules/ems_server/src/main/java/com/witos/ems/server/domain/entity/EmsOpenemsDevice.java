package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_openems_device")
public class EmsOpenemsDevice extends TenantEntity
{
    @TableId
    private Long id;

    private Long endpointId;

    private String edgeId;

    private String resourceGroupId;

    private String primaryComponentId;

    private String deviceType;

    private String displayName;

    private Long companyId;

    private Long stationId;

    private Long businessDeviceId;

    private String sourceType;

    private String status;

    private String desiredConfigHash;

    private String desiredConfigJson;

    private String rawJson;

    private Date disabledAt;

    private Date lastSeenAt;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
