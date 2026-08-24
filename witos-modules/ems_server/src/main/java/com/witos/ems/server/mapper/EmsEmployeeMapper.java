package com.witos.ems.server.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.witos.ems.server.auth.EmsDataScope;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface EmsEmployeeMapper
{
    IPage<Map<String, Object>> selectEmployeePage(Page<Map<String, Object>> page,
                                                  @Param("query") Map<String, Object> query,
                                                  @Param("scope") EmsDataScope scope);

    List<Map<String, Object>> selectEmployeeList(@Param("query") Map<String, Object> query,
                                                 @Param("scope") EmsDataScope scope);

    Map<String, Object> selectEmployeeDetail(@Param("userId") Long userId,
                                             @Param("scope") EmsDataScope scope);
}
