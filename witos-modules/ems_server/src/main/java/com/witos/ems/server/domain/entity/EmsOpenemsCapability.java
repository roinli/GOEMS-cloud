package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_openems_capability")
public class EmsOpenemsCapability extends TenantEntity
{
    @TableId
    private Long id;
    private Long endpointId;
    private String edgeId;
    private String componentId;
    private String capabilityKey;
    private String route;
    private String requestSchema;
    private String responseSchema;
    private String guards;
    private String channelSchema;
    private String factorySchema;
    private String version;
    private String status;
    private Date lastSeenAt;
    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
