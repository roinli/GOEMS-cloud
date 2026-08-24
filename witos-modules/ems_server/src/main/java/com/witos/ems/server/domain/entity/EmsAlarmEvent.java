package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_alarm_event")
public class EmsAlarmEvent extends TenantEntity
{
    @TableId
    private Long id;

    private Long companyId;

    private Long stationId;

    private Long deviceId;

    private Long ruleId;

    private String alarmCode;

    private String alarmName;

    private String severity;

    private String alarmStatus;

    private Integer occurrenceCount;

    private Date firstTime;

    private Date lastTime;

    private Date clearTime;

    private String clearType;

    private String clearBy;

    private Date ackTime;

    private String ackBy;

    private String sourceType;

    private String sourcePayload;
}
