package com.witos.ems.server.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface EmsAuthScopeMapper
{
    Map<String, Object> selectUserProfile(@Param("userId") Long userId);

    List<String> selectUserRoleKeys(@Param("userId") Long userId);

    List<Map<String, Object>> selectUserScopes(@Param("userId") Long userId);

    List<Map<String, Object>> selectCompanyTree();

    List<Long> selectCompanyIdsByStationIds(@Param("stationIds") List<Long> stationIds);
}
