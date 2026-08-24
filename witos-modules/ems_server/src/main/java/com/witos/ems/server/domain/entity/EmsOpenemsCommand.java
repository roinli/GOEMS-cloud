package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_openems_command")
public class EmsOpenemsCommand extends TenantEntity
{
    @TableId private Long id;
    private String requestId;
    private Long endpointId;
    private String edgeId;
    private String componentId;
    private String appInstanceId;
    private Long deviceId;
    private String operation;
    private String operationSource;
    private String payloadHash;
    private String payloadJson;
    private String status;
    private Date sentAt;
    private Date responseAt;
    private String errorCode;
    private String errorMessage;
    private String responseJson;
    @TableLogic(value = "0", delval = "2") private String delFlag;
}
