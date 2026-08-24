package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("ems_metric_snapshot")
public class EmsMetricSnapshot extends TenantEntity
{
    @TableId
    private Long id;

    private Long companyId;

    private Long stationId;

    private Long deviceId;

    private String metricKey;

    private BigDecimal metricValue;

    private String metricText;

    private String unit;

    private Date sampleTime;

    private String quality;

    private String qualityReason;
}