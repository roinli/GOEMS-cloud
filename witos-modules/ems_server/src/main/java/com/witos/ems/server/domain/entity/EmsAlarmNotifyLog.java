package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_alarm_notify_log")
public class EmsAlarmNotifyLog extends TenantEntity
{
    @TableId
    private Long id;

    private Long companyId;

    private Long stationId;

    private Long deviceId;

    private Long eventId;

    private Long ruleId;

    private String ruleName;

    private String channelType;

    private String receiver;

    private String sendStatus;

    private String subject;

    private String content;

    private String errorMessage;

    private Date triggeredAt;

    private Date sentAt;
}
