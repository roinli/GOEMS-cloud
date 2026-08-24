package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("ems_metric_history_5min")
public class EmsMetricHistory5Min extends TenantEntity
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

    private String metricKey;

    private String reportMethod;

    private String sourceRole;

    private Integer sourcePriority;

    private Date bucketTime;

    private BigDecimal metricValue;

    private String metricText;

    private String unit;

    private String quality;

    private String qualityReason;

    private Date sourceSampleTime;
}