package com.witos.ems.server.mapper;

import com.witos.common.mybatisplus.mapper.BaseMapperX;
import com.witos.ems.server.domain.entity.EmsUserProfile;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface EmsUserProfileMapper extends BaseMapperX<EmsUserProfile>
{
    @Update("update ems_user_profile p "
            + "set primary_company_id = #{companyId} "
            + "where p.tenant_id = #{tenantId} "
            + "and p.primary_company_id = 0 "
            + "and p.is_default_installer_admin = '1' "
            + "and p.status = '0' "
            + "and not exists (select 1 from ems_company c "
            + "where c.tenant_id = #{tenantId} and c.id <> #{companyId} and c.del_flag = '0')")
    int bindFirstCompanyToDefaultAdmin(@Param("tenantId") Long tenantId,
                                       @Param("companyId") Long companyId);
}
