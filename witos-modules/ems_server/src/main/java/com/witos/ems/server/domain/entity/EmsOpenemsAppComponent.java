package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

@Data
@TableName("ems_openems_app_component")
public class EmsOpenemsAppComponent extends TenantEntity
{
    @TableId
    private Long id;
    private Long endpointId;
    private String edgeId;
    private String appInstanceId;
    private String componentId;
    private String role;
    private String source;
    private String status;
    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
