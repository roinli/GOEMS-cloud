package com.witos.ems.server.openems;

import com.witos.common.core.exception.ServiceException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class EmsChannelMappingMethodSupport
{
    private static final Set<String> SAMPLE_METHODS = new HashSet<String>(Arrays.asList("AVG", "LAST", "MAX", "MIN", "DELTA", "PERIOD_SUM", "COUNT"));

    private static final Set<String> REPORT_METHODS = new HashSet<String>(Arrays.asList("SUM", "AVG", "MAX", "MIN", "LAST"));

    private EmsChannelMappingMethodSupport()
    {
    }

    public static String sampleMethod(String method)
    {
        return validate(method, "AVG", SAMPLE_METHODS, "sample_method");
    }

    public static String reportMethod(String method)
    {
        return validate(method, "SUM", REPORT_METHODS, "report_method");
    }

    private static String validate(String method, String defaultMethod, Set<String> allowedMethods, String fieldName)
    {
        String normalizedMethod = method == null || method.trim().isEmpty() ? defaultMethod : method.trim().toUpperCase();
        if (!allowedMethods.contains(normalizedMethod))
        {
            throw new ServiceException("ems_channel_mapping." + fieldName + "配置非法：" + method);
        }
        return normalizedMethod;
    }
}