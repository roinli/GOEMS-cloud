package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("ems_channel_mapping")
public class EmsChannelMapping
{
    @TableId
    private Long id;

    private String metricKey;

    private String metricName;

    private String deviceType;

    private String componentIdPattern;

    private String channelAddress;

    private String unit;

    private BigDecimal scaleFactor;

    private String valueType;

    private String sampleMethod;

    private String reportMethod;

    private String sourceRole;

    private Integer sourcePriority;

    private String enabled;
}