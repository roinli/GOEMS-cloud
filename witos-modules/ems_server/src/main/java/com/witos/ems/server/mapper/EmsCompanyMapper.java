package com.witos.ems.server.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.witos.common.mybatisplus.mapper.BaseMapperX;
import com.witos.ems.server.auth.EmsDataScope;
import com.witos.ems.server.domain.entity.EmsCompany;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface EmsCompanyMapper extends BaseMapperX<EmsCompany>
{
    IPage<Map<String, Object>> selectCompanyPage(Page<Map<String, Object>> page,
                                                 @Param("query") Map<String, Object> query,
                                                 @Param("scope") EmsDataScope scope);

    List<Map<String, Object>> selectCompanyList(@Param("query") Map<String, Object> query,
                                                @Param("scope") EmsDataScope scope);

    Map<String, Object> selectCompanyDetail(@Param("id") Long id,
                                            @Param("scope") EmsDataScope scope);
}
