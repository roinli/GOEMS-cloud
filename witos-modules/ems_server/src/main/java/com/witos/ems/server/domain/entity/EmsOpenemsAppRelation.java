package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

@Data
@TableName("ems_openems_app_relation")
public class EmsOpenemsAppRelation extends TenantEntity
{
    @TableId
    private Long id;
    private Long endpointId;
    private String edgeId;
    private String sourceInstanceId;
    private String targetInstanceId;
    private String dependencyKey;
    private String status;
    private String rawJson;
    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
