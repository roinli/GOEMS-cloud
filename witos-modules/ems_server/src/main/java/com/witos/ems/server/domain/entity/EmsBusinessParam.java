package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

@Data
@TableName("ems_business_param")
public class EmsBusinessParam extends TenantEntity
{
    @TableId
    private Long id;

    private Long companyId;

    private Long stationId;

    private String paramKey;

    private String paramValue;

    private String valueType;

    private String scopeType;

    private String status;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
