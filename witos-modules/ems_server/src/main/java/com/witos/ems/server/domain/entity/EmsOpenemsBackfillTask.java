package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_openems_backfill_task")
public class EmsOpenemsBackfillTask extends TenantEntity
{
    @TableId
    private Long id;

    private Long deviceId;
    private Long endpointId;
    private String edgeId;
    private String componentId;
    private Date fromTime;
    private Date toTime;
    private String state;
    private String source;
    private java.math.BigDecimal progress;
    private String lastError;
    private String reportRebuildState;
    private Date startedAt;
    private Date finishedAt;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
