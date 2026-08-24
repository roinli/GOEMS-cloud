package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

@Data
@TableName("ems_station_view_relation")
public class EmsStationViewRelation extends TenantEntity
{
    @TableId
    private Long id;

    private Long tabId;

    private Long sourceNodeId;

    private Long targetNodeId;

    private String relationType;

    private String lineStyleJson;

    private Integer sortNo;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
