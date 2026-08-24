package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_sync_log")
public class EmsSyncLog extends TenantEntity
{
    @TableId
    private Long id;

    private Long companyId;

    private Long stationId;

    private String syncType;

    private String bizKey;

    private String status;

    private Date startedAt;

    private Date finishedAt;

    private String errorMessage;

    private String issueKey;

    private Long occurrenceCount;

    private Date firstOccurredAt;

    private Date lastOccurredAt;

    private String errorHash;
}
