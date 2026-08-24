package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

@Data
@TableName("ems_alarm_mail_config")
public class EmsAlarmMailConfig extends TenantEntity
{
    @TableId
    private Long id;

    private Long companyId;

    private String smtpHost;

    private Integer smtpPort;

    private String smtpUsername;

    private String smtpPassword;

    private String fromAddress;

    private String fromName;

    private String sslEnabled;

    private String starttlsEnabled;

    private String authEnabled;

    private String enabled;
}
