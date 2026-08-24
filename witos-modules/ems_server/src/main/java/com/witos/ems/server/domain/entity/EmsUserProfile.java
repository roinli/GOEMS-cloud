package com.witos.ems.server.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.witos.common.core.web.domain.TenantEntity;
import lombok.Data;

@Data
@TableName("ems_user_profile")
public class EmsUserProfile extends TenantEntity
{
    @TableId
    private Long id;

    private Long userId;

    private Long primaryCompanyId;

    private String isDefaultInstallerAdmin;

    private String status;
}
