package com.witos.common.core.exception.user;

import com.witos.common.core.exception.base.BaseException;

/**
 * 用户信息异常类
 *
 * @author witos
 */
public class UserException extends BaseException
{
    private static final long serialVersionUID = 1L;

    public UserException(String code, Object[] args)
    {
        super("user", code, args, null);
    }
}
