package com.witos.ems.server.support;

import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.common.security.utils.SecurityUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

public final class EmsRequestSupport
{
    private EmsRequestSupport()
    {
    }

    public static Long currentTenantId()
    {
        Long tenantId = SecurityUtils.getTenantId();
        return tenantId == null ? 9999L : tenantId;
    }

    public static boolean isPlatformAdmin()
    {
        return SecurityUtils.isSuperAdmin();
    }

    public static Long requestedTenantId(Map<String, Object> body)
    {
        Long tenantId = body == null ? null : asLong(body.get("tenantId"));
        return isPlatformAdmin() && tenantId != null ? tenantId : currentTenantId();
    }

    public static String currentUsername()
    {
        String username = SecurityUtils.getUsername();
        return StringUtils.isEmpty(username) ? "system" : username;
    }

    public static Long asLong(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Number)
        {
            return ((Number) value).longValue();
        }
        String str = String.valueOf(value);
        if (StringUtils.isEmpty(str))
        {
            return null;
        }
        return Long.parseLong(str);
    }

    public static Long coalesceId(Map<String, Object> body, String... keys)
    {
        if (body == null)
        {
            return null;
        }
        for (String key : keys)
        {
            Long value = asLong(body.get(key));
            if (value != null)
            {
                return value;
            }
        }
        return null;
    }

    public static Long requiredLong(Map<String, Object> body, String key, String message)
    {
        Long value = asLong(body.get(key));
        if (value == null)
        {
            throw new ServiceException(message);
        }
        return value;
    }

    public static Integer asInteger(Object value, int defaultValue)
    {
        if (value == null || StringUtils.isEmpty(String.valueOf(value)))
        {
            return defaultValue;
        }
        if (value instanceof Number)
        {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    public static BigDecimal asBigDecimal(Object value)
    {
        if (value == null || StringUtils.isEmpty(String.valueOf(value)))
        {
            return null;
        }
        if (value instanceof BigDecimal)
        {
            return (BigDecimal) value;
        }
        return new BigDecimal(String.valueOf(value));
    }

    public static String stringValue(Object value)
    {
        return value == null ? "" : String.valueOf(value);
    }

    public static String defaultString(Object value, String defaultValue)
    {
        String str = value == null ? null : String.valueOf(value);
        return StringUtils.isEmpty(str) ? defaultValue : str;
    }

    public static Object nullable(Map<String, Object> body, String key)
    {
        return body.containsKey(key) ? body.get(key) : null;
    }

    public static Timestamp nullableTimestamp(Object value)
    {
        if (value == null || StringUtils.isEmpty(String.valueOf(value)))
        {
            return null;
        }
        if (value instanceof Timestamp)
        {
            return (Timestamp) value;
        }
        if (value instanceof Date)
        {
            return new Timestamp(((Date) value).getTime());
        }
        String str = String.valueOf(value).trim();
        if (StringUtils.isEmpty(str))
        {
            return null;
        }
        if (str.matches("\\d{4}-\\d{2}-\\d{2}"))
        {
            return Timestamp.valueOf(str + " 00:00:00");
        }
        if (str.contains("T"))
        {
            str = str.replace("T", " ");
            if (str.endsWith("Z"))
            {
                str = str.substring(0, str.length() - 1);
            }
            int dotIndex = str.indexOf('.');
            if (dotIndex > 0)
            {
                str = str.substring(0, dotIndex);
            }
        }
        return Timestamp.valueOf(str);
    }

    public static Timestamp defaultTimestamp(Object value, Date defaultValue)
    {
        Timestamp timestamp = nullableTimestamp(value);
        return timestamp == null ? new Timestamp(defaultValue.getTime()) : timestamp;
    }

    public static List<Long> asLongList(Object value)
    {
        if (!(value instanceof List))
        {
            return new ArrayList<Long>();
        }
        List<Long> result = new ArrayList<Long>();
        for (Object item : (List<?>) value)
        {
            Long converted = asLong(item);
            if (converted != null)
            {
                result.add(converted);
            }
        }
        return result;
    }
}
