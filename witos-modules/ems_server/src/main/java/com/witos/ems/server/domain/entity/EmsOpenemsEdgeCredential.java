package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

@Data
@TableName("ems_openems_edge_credential")
public class EmsOpenemsEdgeCredential extends TenantEntity
{
    @TableId
    private Long id;

    private Long endpointId;

    private String edgeId;

    private String apiKeyCiphertext;

    private String setupPasswordCiphertext;

    private Integer credentialVersion;

    private String displayAuditJson;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
