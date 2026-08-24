package com.witos.ems.server.support;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.witos.common.core.text.Convert;
import com.witos.common.core.utils.ServletUtils;
import com.witos.common.mybatisplus.constant.MybatisPageConstants;

public final class EmsPageSupport
{
    private EmsPageSupport()
    {
    }

    public static <T> Page<T> page()
    {
        return new Page<T>(
                Convert.toLong(ServletUtils.getParameterToInt(MybatisPageConstants.PAGE_NUM), 1L),
                Convert.toLong(ServletUtils.getParameterToInt(MybatisPageConstants.PAGE_SIZE), 10L));
    }
}
