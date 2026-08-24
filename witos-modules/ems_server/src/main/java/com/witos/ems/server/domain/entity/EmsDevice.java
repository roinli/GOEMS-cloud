package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_device")
public class EmsDevice extends TenantEntity
{
    @TableId
    private Long id;

    private Long companyId;

    private Long stationId;

    private Long controllerId;

    private String deviceCode;

    private String deviceName;

    private String deviceType;

    private java.math.BigDecimal ratedCapacity;

    private String model;

    private String serialNo;

    private String manufacturer;

    private String firmwareVersion;

    private Date lastHeartbeatTime;

    private String controllerVersion;

    private Date installDate;

    private String commStatus;

    private String status;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
