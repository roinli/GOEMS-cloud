package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("ems_price_period")
public class EmsPricePeriod extends TenantEntity
{
    @TableId
    private Long id;

    private Long ruleId;

    @TableField(exist = false)
    private String periodName;

    private String periodType;

    private String startTime;

    private String endTime;

    @TableField("price")
    private BigDecimal priceValue;

    private String weekdayMask;

    private Integer sortNo;
}
