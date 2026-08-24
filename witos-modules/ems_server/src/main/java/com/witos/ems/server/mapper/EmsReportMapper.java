package com.witos.ems.server.mapper;

import com.witos.ems.server.auth.EmsDataScope;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface EmsReportMapper
{
    Long countReportRows(@Param("reportType") String reportType,
                         @Param("periodType") String periodType,
                         @Param("query") Map<String, Object> query,
                         @Param("scope") EmsDataScope scope);

    List<Map<String, Object>> selectReportRows(@Param("reportType") String reportType,
                                               @Param("periodType") String periodType,
                                               @Param("query") Map<String, Object> query,
                                               @Param("scope") EmsDataScope scope);

    Map<String, Object> selectLifecycleSummary(@Param("reportType") String reportType,
                                               @Param("periodType") String periodType,
                                               @Param("query") Map<String, Object> query,
                                               @Param("scope") EmsDataScope scope);
}
