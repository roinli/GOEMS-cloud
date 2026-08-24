package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("ems_alarm_rule")
public class EmsAlarmRule extends TenantEntity
{
    @TableId
    private Long id;

    private Long companyId;

    private Long stationId;

    private String ruleName;

    private String metricKey;

    private String deviceType;

    private String conditionOp;

    private BigDecimal thresholdValue;

    private Integer durationSeconds;

    private String severity;

    private String enabled;

    private String notifyEnabled;

    private String notifyChannels;

    private String notifyTargets;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
