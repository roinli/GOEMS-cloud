package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

@Data
@TableName("ems_optimizer_component_binding")
public class EmsOptimizerComponentBinding extends TenantEntity
{
    @TableId
    private Long id;

    private Long tabId;

    private Long optimizerDeviceId;

    private Long moduleDeviceId;

    private Long componentId;

    private Integer bindingOrder;

    @TableLogic(value = "0", delval = "2")
    private String delFlag;
}
