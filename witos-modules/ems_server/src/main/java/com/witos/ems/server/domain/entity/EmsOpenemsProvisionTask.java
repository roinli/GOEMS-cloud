package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_openems_provision_task")
public class EmsOpenemsProvisionTask extends TenantEntity
{
    @TableId private Long id;
    private Long deviceId;
    private Long endpointId;
    private String edgeId;
    private String desiredHash;
    private String state;
    private String step;
    private Integer attempt;
    private String lastError;
    private String componentId;
    private String bridgeId;
    private String conflictDetail;
    private String desiredJson;
    private String verifyJson;
    private Date startedAt;
    private Date finishedAt;
    @TableLogic(value = "0", delval = "2") private String delFlag;
}
