package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_openems_edge")
public class EmsOpenemsEdge extends TenantEntity
{
    @TableId
    private Long id;

    private Long endpointId;

    private String edgeId;

    private String edgeKey;

    private String edgeName;

    private String sourceType;

    private Long companyId;

    private Long stationId;

    private String onlineStatus;

    private Date lastHeartbeatAt;

    private Date lastSeenAt;

    private Date lastSyncAt;

    private String dataCapabilityStatus;

    private String commentMarker;

    private String rawMetadataJson;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
