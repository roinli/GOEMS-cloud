package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_metric_sync_cursor")
public class EmsMetricSyncCursor extends TenantEntity
{
    @TableId
    private Long id;

    private Long deviceComponentId;

    private Date lastSuccessBucketTime;

    private Date lastAttemptTime;

    private String status;

    private String errorMessage;
}