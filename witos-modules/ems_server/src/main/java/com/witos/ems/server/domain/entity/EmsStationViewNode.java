package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("ems_station_view_node")
public class EmsStationViewNode extends TenantEntity
{
    @TableId
    private Long id;

    private Long tabId;

    private String nodeType;

    private Long deviceId;

    private Long componentId;

    private BigDecimal x;

    private BigDecimal y;

    private BigDecimal width;

    private BigDecimal height;

    private String nodeStyleJson;

    private Integer sortNo;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
