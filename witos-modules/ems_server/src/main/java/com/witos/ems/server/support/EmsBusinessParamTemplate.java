package com.witos.ems.server.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public enum EmsBusinessParamTemplate
{
    CO2_FACTOR("co2_factor", "二氧化碳减排转换系数", "0.475", "NUMBER", "kg/kWh", "发电量转CO2减排量（每kWh减排二氧化碳千克数）", "TENANT", "COMPANY", "STATION"),
    STANDARD_COAL_FACTOR("standard_coal_factor", "节约标准煤转换系数", "0.4", "NUMBER", "kg/kWh", "发电量转标准煤节约量（每kWh节约标准煤千克数）", "TENANT", "COMPANY", "STATION"),
    TREE_FACTOR("tree_factor", "等效植树量转换系数", "18.3", "NUMBER", "kg/棵", "等效植树量=CO2减排量÷该系数（每棵树每年吸收二氧化碳千克数）", "TENANT", "COMPANY", "STATION");

    private final String key;
    private final String label;
    private final String defaultValue;
    private final String valueType;
    private final String unit;
    private final String description;
    private final List<String> scopeTypes;

    EmsBusinessParamTemplate(String key,
                             String label,
                             String defaultValue,
                             String valueType,
                             String unit,
                             String description,
                             String... scopeTypes)
    {
        this.key = key;
        this.label = label;
        this.defaultValue = defaultValue;
        this.valueType = valueType;
        this.unit = unit;
        this.description = description;
        this.scopeTypes = Arrays.asList(scopeTypes);
    }

    public String getKey()
    {
        return key;
    }

    public String getLabel()
    {
        return label;
    }

    public String getDefaultValue()
    {
        return defaultValue;
    }

    public String getValueType()
    {
        return valueType;
    }

    public String getUnit()
    {
        return unit;
    }

    public String getDescription()
    {
        return description;
    }

    public List<String> getScopeTypes()
    {
        return scopeTypes;
    }

    public boolean supportsScope(String scopeType)
    {
        return scopeType != null && scopeTypes.contains(scopeType.toUpperCase());
    }

    public Map<String, Object> toMap()
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("paramKey", key);
        row.put("templateName", label);
        row.put("defaultValue", defaultValue);
        row.put("valueType", valueType);
        row.put("unit", unit);
        row.put("description", description);
        row.put("scopeTypes", new ArrayList<String>(scopeTypes));
        row.put("coreParam", true);
        return row;
    }

    public static EmsBusinessParamTemplate fromKey(String key)
    {
        if (key == null)
        {
            return null;
        }
        for (EmsBusinessParamTemplate item : values())
        {
            if (item.key.equalsIgnoreCase(key))
            {
                return item;
            }
        }
        return null;
    }

    public static List<Map<String, Object>> optionList()
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (EmsBusinessParamTemplate item : values())
        {
            rows.add(item.toMap());
        }
        return rows;
    }
}
