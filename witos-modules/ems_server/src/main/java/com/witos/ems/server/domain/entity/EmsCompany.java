package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("ems_company")
public class EmsCompany extends TenantEntity
{
    @TableId
    private Long id;

    private Long parentId;

    private String ancestors;

    private String companyName;

    private String companyDesc;

    private String country;

    private String province;

    private String city;

    private String address;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private String website;

    private String recordNo;

    private Integer orderNum;

    private String status;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
