package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_openems_endpoint_source")
public class EmsOpenemsEndpointSource extends TenantEntity
{
    @TableId
    private Long id;

    private Long endpointId;

    private String sourceType;

    private String url;

    private String version;

    private String queryLanguage;

    private String org;

    private String bucket;

    private String databaseName;

    private String retentionPolicy;

    private String measurement;

    private String edgeTag;

    private String timezone;

    private String credentialRef;

    private String queryConfigJson;

    private Integer connectTimeoutSeconds;

    private Integer readTimeoutSeconds;

    private String enabled;

    private String lastTestStatus;

    private Date lastTestAt;

    private String lastErrorCode;

    private String lastErrorMessage;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
