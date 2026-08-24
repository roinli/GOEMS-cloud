package com.witos.ems.server.openems;

import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class OpenemsBindTokenCodec
{
    private static final String SEPARATOR = "\t";

    private OpenemsBindTokenCodec()
    {
    }

    public static String encode(OpenemsComponentCandidate candidate)
    {
        String value = join(candidate.getServerEndpointId(), candidate.getEdgeId(), candidate.getComponentId(), candidate.getSerialNo(),
                candidate.getComponentType(), candidate.getComponentAlias(), candidate.getParentEdgeId(), candidate.getParentComponentId(),
                candidate.getModel(), candidate.getManufacturer(), candidate.getFirmwareVersion());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static OpenemsComponentCandidate decode(String token)
    {
        if (StringUtils.isEmpty(token))
        {
            throw new ServiceException("请选择SN查询到的OpenEMS组件");
        }
        try
        {
            String value = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = value.split(SEPARATOR, -1);
            if (parts.length < 11)
            {
                throw new ServiceException("组件绑定凭证格式不正确");
            }
            OpenemsComponentCandidate candidate = new OpenemsComponentCandidate();
            candidate.setServerEndpointId(Long.parseLong(parts[0]));
            candidate.setEdgeId(parts[1]);
            candidate.setComponentId(parts[2]);
            candidate.setSerialNo(parts[3]);
            candidate.setComponentType(parts[4]);
            candidate.setComponentAlias(parts[5]);
            candidate.setParentEdgeId(parts[6]);
            candidate.setParentComponentId(parts[7]);
            candidate.setModel(parts[8]);
            candidate.setManufacturer(parts[9]);
            candidate.setFirmwareVersion(parts[10]);
            candidate.setBindToken(token);
            return candidate;
        }
        catch (IllegalArgumentException ex)
        {
            throw new ServiceException("组件绑定凭证无效");
        }
    }

    private static String join(Object... values)
    {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++)
        {
            if (i > 0)
            {
                builder.append(SEPARATOR);
            }
            builder.append(values[i] == null ? "" : String.valueOf(values[i]).replace(SEPARATOR, " "));
        }
        return builder.toString();
    }
}