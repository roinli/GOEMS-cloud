package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

@Data
@TableName("ems_openems_protocol_template")
public class EmsOpenemsProtocolTemplate extends TenantEntity
{
    @TableId
    private Long id;
    private Long endpointId;
    private String factoryPid;
    private String appId;
    private String protocolType;
    private String driverType;
    private String communicationType;
    private String schemaJson;
    private String defaultJson;
    private String adaptationStatus;
    private String baselineCommit;
    private String enabled;
    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
