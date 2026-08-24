package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("ems_price_rule")
public class EmsPriceRule extends TenantEntity
{
    @TableId
    private Long id;

    private Long companyId;

    private String ruleName;

    private String priceType;

    private String priceMode;

    private String currency;

    private BigDecimal basePrice;

    private String isDefault;

    private String status;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
