package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_openems_component")
public class EmsOpenemsComponent extends TenantEntity
{
    @TableId
    private Long id;
    private Long endpointId;
    private String edgeId;
    private String componentId;
    private String factoryPid;
    private String componentType;
    private String alias;
    private String natureJson;
    private String parentComponentId;
    private String status;
    private Date lastSeenAt;
    private String configHash;
    private String rawJson;
    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
