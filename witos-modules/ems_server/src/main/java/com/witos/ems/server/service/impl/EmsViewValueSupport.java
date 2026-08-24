package com.witos.ems.server.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class EmsViewValueSupport
{
    private EmsViewValueSupport()
    {
    }

    static Long asLong(Object value)
    {
        if (value == null)
        {
            return null;
        }
        if (value instanceof Number)
        {
            return ((Number) value).longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    static BigDecimal asDecimal(Object value)
    {
        if (value == null)
        {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal)
        {
            return (BigDecimal) value;
        }
        return new BigDecimal(String.valueOf(value));
    }

    static BigDecimal asBigDecimal(Object value)
    {
        return asDecimal(value);
    }

    static int asInt(Object value)
    {
        if (value == null)
        {
            return 0;
        }
        if (value instanceof Number)
        {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    static List<Long> idList(List<Map<String, Object>> rows, String field)
    {
        List<Long> ids = new ArrayList<Long>();
        for (Map<String, Object> row : rows)
        {
            Long id = asLong(row.get(field));
            if (id != null)
            {
                ids.add(id);
            }
        }
        return ids;
    }

    static Map<Long, BigDecimal> decimalMap(List<Map<String, Object>> rows, String keyField, String valueField)
    {
        Map<Long, BigDecimal> result = new LinkedHashMap<Long, BigDecimal>();
        for (Map<String, Object> row : rows)
        {
            Long key = asLong(row.get(keyField));
            if (key != null)
            {
                result.put(key, asDecimal(row.get(valueField)));
            }
        }
        return result;
    }

    static Map<Long, Integer> integerMap(List<Map<String, Object>> rows, String keyField, String valueField)
    {
        Map<Long, Integer> result = new LinkedHashMap<Long, Integer>();
        for (Map<String, Object> row : rows)
        {
            Long key = asLong(row.get(keyField));
            if (key != null)
            {
                result.put(key, asInt(row.get(valueField)));
            }
        }
        return result;
    }
}
