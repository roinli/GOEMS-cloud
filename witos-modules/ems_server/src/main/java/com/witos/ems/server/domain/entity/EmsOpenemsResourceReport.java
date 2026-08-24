package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("ems_openems_resource_report")
public class EmsOpenemsResourceReport extends TenantEntity
{
    @TableId
    private Long id;
    private Long deviceId;
    private Long endpointId;
    private String edgeId;
    private String componentId;
    private Date statTime;
    private String valuesJson;
    private String source;
    private String dataQuality;
    private String qualityReason;
    private Long companyId;
    private Long stationId;
    private String revenueStatus;
    private BigDecimal revenueAmount;
    private String revenueQualityReason;
    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
