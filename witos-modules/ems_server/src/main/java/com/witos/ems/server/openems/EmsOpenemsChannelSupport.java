package com.witos.ems.server.openems;

import com.witos.ems.server.domain.entity.EmsChannelMapping;
import com.witos.ems.server.domain.entity.EmsDeviceComponent;

public final class EmsOpenemsChannelSupport
{
    private EmsOpenemsChannelSupport()
    {
    }

    public static String channelAddress(EmsDeviceComponent component, EmsChannelMapping mapping)
    {
        String address = mapping.getChannelAddress();
        if (address == null)
        {
            return "";
        }
        if (address.contains("{componentId}"))
        {
            return address.replace("{componentId}", component.getComponentId());
        }
        if (address.contains("/"))
        {
            return address;
        }
        return component.getComponentId() + "/" + address;
    }

    public static boolean matches(EmsDeviceComponent component, EmsChannelMapping mapping)
    {
        String pattern = mapping.getComponentIdPattern();
        if (pattern == null || pattern.length() == 0)
        {
            return true;
        }
        return component.getComponentId() != null && component.getComponentId().matches(pattern);
    }
}