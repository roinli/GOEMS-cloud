package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_openems_app_instance")
public class EmsOpenemsAppInstance extends TenantEntity
{
    @TableId
    private Long id;
    private Long endpointId;
    private String edgeId;
    private String instanceId;
    private String appId;
    private String alias;
    private String propertiesJson;
    private String warningsJson;
    private String status;
    private Date lastSeenAt;
    private String rawJson;
    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
