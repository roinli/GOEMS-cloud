package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_station_edge")
public class EmsStationEdge extends TenantEntity
{
    @TableId
    private Long id;

    private Long companyId;

    private Long stationId;

    private Long serverEndpointId;

    private String edgeId;

    private String edgeName;

    private String onlineStatus;

    private String sumState;

    private Date lastSeenTime;

    private String enabled;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
