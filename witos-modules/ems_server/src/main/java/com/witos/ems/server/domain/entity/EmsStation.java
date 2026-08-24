package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
@TableName("ems_station")
public class EmsStation extends TenantEntity
{
    @TableId
    private Long id;

    private Long companyId;

    private String stationCode;

    private String stationName;

    private String stationType;

    private String country;

    private String contactName;

    private String contactPhone;

    private String runMode;

    private String timezone;

    private String imageUrl;

    private BigDecimal capacityKw;

    private String address;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private Date commissionDate;

    private String status;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
