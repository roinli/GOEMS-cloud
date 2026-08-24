package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

@Data
@TableName("ems_openems_component_relation")
public class EmsOpenemsComponentRelation extends TenantEntity
{
    @TableId
    private Long id;
    private Long endpointId;
    private String edgeId;
    private String parentComponentId;
    private String childComponentId;
    private String relationType;
    private String source;
    private String status;
    private String rawJson;
    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
