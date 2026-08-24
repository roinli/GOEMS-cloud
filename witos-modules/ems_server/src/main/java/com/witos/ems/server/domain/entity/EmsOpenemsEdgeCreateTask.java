package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_openems_edge_create_task")
public class EmsOpenemsEdgeCreateTask extends TenantEntity
{
    @TableId
    private Long id;

    private String requestNo;

    private Long endpointId;

    private String edgeName;

    private String commentMarker;

    private String state;

    private String backendEdgeId;

    private String errorCode;

    private String errorMessage;

    private Date requestedAt;

    private Date finishedAt;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
