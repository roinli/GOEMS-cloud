package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

import java.util.Date;

@Data
@TableName("ems_energy_flow_session")
public class EmsEnergyFlowSession extends TenantEntity
{
    @TableId
    private Long id;

    private Long companyId;

    private Long stationId;

    private Long userId;

    private Integer sessionSeconds;

    private Date startedAt;

    private Date expiresAt;

    private String sessionStatus;

    private String requestSource;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
