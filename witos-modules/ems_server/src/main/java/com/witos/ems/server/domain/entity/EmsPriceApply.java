package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_price_apply")
public class EmsPriceApply extends TenantEntity
{
    @TableId
    private Long id;

    private Long companyId;

    private Long stationId;

    private Long ruleId;

    private String priceType;

    private Date effectiveStart;

    private Date effectiveEnd;

    private String permanent;

    private String status;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
