package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_report_sync_task")
public class EmsReportSyncTask extends TenantEntity
{
    @TableId
    private Long id;

    private Long companyId;

    private Long stationId;

    private String reportType;

    private String periodType;

    private Date rangeStartTime;

    private Date rangeEndTime;

    private String taskKey;

    private String taskStatus;

    private Integer retryCount;

    private Integer affectedRows;

    private String sourceSystem;

    private String errorMessage;

    private Date executeStartTime;

    private Date executeEndTime;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
