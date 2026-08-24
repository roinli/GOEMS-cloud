package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

@Data
@TableName("ems_server_endpoint")
public class EmsServerEndpoint extends TenantEntity
{
    @TableId
    private Long id;

    private String scopeType;

    private String endpointCode;

    private String endpointName;

    private String baseUrl;

    private String authType;

    private String credentialRef;

    private String enabled;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}