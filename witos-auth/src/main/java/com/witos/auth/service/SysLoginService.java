package com.witos.auth.service;

import com.witos.auth.config.EmsMailProperties;
import com.witos.auth.config.EmsRegistrationProperties;
import com.witos.common.core.constant.CacheConstants;
import com.witos.common.core.text.Convert;
import com.witos.common.redis.service.RedisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.witos.common.core.constant.Constants;
import com.witos.common.core.constant.SecurityConstants;
import com.witos.common.core.constant.UserConstants;
import com.witos.common.core.domain.R;
import com.witos.common.core.enums.UserStatus;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.common.core.utils.ip.IpUtils;
import com.witos.common.message.mail.EmailUtil;
import com.witos.common.security.utils.SecurityUtils;
import com.witos.system.api.RemoteTenantService;
import com.witos.system.api.RemoteUserService;
import com.witos.system.api.domain.SysTenantRegister;
import com.witos.system.api.domain.SysUser;
import com.witos.system.api.model.LoginUser;

import java.security.SecureRandom;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * 登录校验方法
 *
 * @author witos
 */
@Component
public class SysLoginService
{
    private static final String REGISTER_EMAIL_CODE_KEY = "register_email_codes:";
    private static final String REGISTER_EMAIL_LIMIT_KEY = "register_email_limits:email:";
    private static final String REGISTER_IP_LIMIT_KEY = "register_email_limits:ip:";
    private static final String REGISTER_DEVICE_LIMIT_KEY = "register_email_limits:device:";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private RemoteUserService remoteUserService;

    @Autowired
    private RemoteTenantService remoteTenantService;

    @Autowired
    private SysPasswordService passwordService;

    @Autowired
    private SysRecordLogService recordLogService;

    @Autowired
    private RedisService redisService;

    @Autowired
    private EmailUtil emailUtil;

    @Autowired
    private EmsRegistrationProperties registrationProperties;

    @Autowired
    private EmsMailProperties mailProperties;
    /**
     * 登录
     */
    public LoginUser login(String username, String password)
    {
        // 用户名或密码为空 错误
        if (StringUtils.isAnyBlank(username, password))
        {
            recordLogService.recordLogininfor(username, Constants.LOGIN_FAIL, "用户/密码必须填写");
            throw new ServiceException("用户/密码必须填写");
        }
        // 密码如果不在指定范围内 错误
        if (password.length() < UserConstants.PASSWORD_MIN_LENGTH
                || password.length() > UserConstants.PASSWORD_MAX_LENGTH)
        {
            recordLogService.recordLogininfor(username, Constants.LOGIN_FAIL, "用户密码不在指定范围");
            throw new ServiceException("用户密码不在指定范围");
        }
        // 用户名不在指定范围内 错误
        if (username.length() < UserConstants.USERNAME_MIN_LENGTH
                || username.length() > UserConstants.USERNAME_MAX_LENGTH)
        {
            recordLogService.recordLogininfor(username, Constants.LOGIN_FAIL, "用户名不在指定范围");
            throw new ServiceException("用户名不在指定范围");
        }
        // IP黑名单校验
        String blackStr = Convert.toStr(redisService.getCacheObject(CacheConstants.SYS_LOGIN_BLACKIPLIST));
        if (IpUtils.isMatchedIp(blackStr, IpUtils.getIpAddr()))
        {
            recordLogService.recordLogininfor(username, Constants.LOGIN_FAIL, "很遗憾，访问IP已被列入系统黑名单");
            throw new ServiceException("很遗憾，访问IP已被列入系统黑名单");
        }
        // 查询用户信息
        R<LoginUser> userResult = remoteUserService.getUserInfo(username, SecurityConstants.INNER);

        if (StringUtils.isNull(userResult) || StringUtils.isNull(userResult.getData()))
        {
            recordLogService.recordLogininfor(username, Constants.LOGIN_FAIL, "登录用户不存在");
            throw new ServiceException("登录用户：" + username + " 不存在");
        }


        if (R.FAIL == userResult.getCode())
        {
            throw new ServiceException(userResult.getMsg());
        }

        LoginUser userInfo = userResult.getData();
        SysUser user = userResult.getData().getSysUser();
        //线程塞入租户ID
        SecurityUtils.setTenantId(Convert.toStr(user.getTenantId()));
        if (UserStatus.DELETED.getCode().equals(user.getDelFlag()))
        {
            recordLogService.recordLogininfor(username, Constants.LOGIN_FAIL, "对不起，您的账号已被删除");
            throw new ServiceException("对不起，您的账号：" + username + " 已被删除");
        }
        if (UserStatus.DISABLE.getCode().equals(user.getStatus()))
        {
            recordLogService.recordLogininfor(username, Constants.LOGIN_FAIL, "用户已停用，请联系管理员");
            throw new ServiceException("对不起，您的账号：" + username + " 已停用");
        }
        //先查询是否被停用了租户
        if (userInfo.getTenantStatus() != null && UserStatus.DISABLE.getCode().equals(userInfo.getTenantStatus().toString()))
        {
            recordLogService.recordLogininfor(username, Constants.LOGIN_FAIL, "当前租户已经被停用，请联系管理员");
            throw new ServiceException("当前租户已经被停用");
        }
        if (userInfo.getTenantEndDate() != null && userInfo.getTenantEndDate().compareTo(new Date()) < 0)
        {
            recordLogService.recordLogininfor(username, Constants.LOGIN_FAIL, "当前租户已超过租赁日期，请联系管理员");
            throw new ServiceException("当前租户已超过租赁日期");
        }

        passwordService.validate(user, password);
        recordLogService.recordLogininfor(username, Constants.LOGIN_SUCCESS, "登录成功");
        return userInfo;
    }

    public void logout(String loginName)
    {
        recordLogService.recordLogininfor(loginName, Constants.LOGOUT, "退出成功");
    }

    /**
     * 注册
     */
    public void register(String username, String password)
    {
        // 用户名或密码为空 错误
        if (StringUtils.isAnyBlank(username, password))
        {
            throw new ServiceException("用户/密码必须填写");
        }
        if (username.length() < UserConstants.USERNAME_MIN_LENGTH
                || username.length() > UserConstants.USERNAME_MAX_LENGTH)
        {
            throw new ServiceException("账户长度必须在2到20个字符之间");
        }
        if (password.length() < UserConstants.PASSWORD_MIN_LENGTH
                || password.length() > UserConstants.PASSWORD_MAX_LENGTH)
        {
            throw new ServiceException("密码长度必须在5到20个字符之间");
        }

        // 注册用户信息
        SysUser sysUser = new SysUser();
        sysUser.setUserName(username);
        sysUser.setNickName(username);
        sysUser.setPassword(SecurityUtils.encryptPassword(password));
        R<?> registerResult = remoteUserService.registerUserInfo(sysUser, SecurityConstants.INNER);

        if (registerResult == null || R.FAIL == registerResult.getCode())
        {
            throw new ServiceException(registerResult == null ? "注册服务无响应" : registerResult.getMsg());
        }
        recordLogService.recordLogininfor(username, Constants.REGISTER, "注册成功");
    }

    public void registerInstaller(SysTenantRegister body)
    {
        assertInstallerRegistrationEnabled();
        if (body == null || StringUtils.isAnyBlank(body.getUsername(), body.getPassword(), body.getCompanyName(), body.getPhonenumber(), body.getEmail()))
        {
            throw new ServiceException("公司名称、账号、密码、手机号、邮箱必须填写");
        }
        if (body.getPassword().length() < UserConstants.PASSWORD_MIN_LENGTH
                || body.getPassword().length() > UserConstants.PASSWORD_MAX_LENGTH)
        {
            throw new ServiceException("密码长度必须在5到20个字符之间");
        }
        R<?> registerResult = remoteTenantService.registerInstaller(body, SecurityConstants.INNER);
        if (R.FAIL == registerResult.getCode())
        {
            throw new ServiceException(registerResult.getMsg());
        }
        recordLogService.recordLogininfor(body.getUsername(), Constants.REGISTER, "安装商注册成功");
    }

    public void sendRegisterEmailCode(String email, String ip, String deviceId)
    {
        assertInstallerRegistrationEnabled();
        if (StringUtils.isBlank(email))
        {
            throw new ServiceException("邮箱必须填写");
        }
        String normalizedEmail = normalizeEmail(email);
        String emailLimitKey = REGISTER_EMAIL_LIMIT_KEY + normalizedEmail;
        String ipLimitKey = REGISTER_IP_LIMIT_KEY + safeRateLimitValue(ip);
        String deviceLimitKey = REGISTER_DEVICE_LIMIT_KEY + safeRateLimitValue(deviceId);
        acquireRateLimit(emailLimitKey, registrationProperties.getEmailRateLimitSeconds());
        acquireRateLimit(ipLimitKey, registrationProperties.getIpRateLimitSeconds());
        acquireRateLimit(deviceLimitKey, registrationProperties.getDeviceRateLimitSeconds());
        String code = String.format("%06d", RANDOM.nextInt(1000000));
        String codeKey = registerEmailCodeKey(normalizedEmail);
        try
        {
            emailUtil.sendSimpleMail(mailProperties.getRegistrationSubject(),
                    "您的注册验证码为：" + code + "，有效期" + registrationProperties.getEmailCodeExpirationMinutes() + "分钟。",
                    normalizedEmail);
            redisService.setCacheObject(codeKey, code,
                    registrationProperties.getEmailCodeExpirationMinutes(), TimeUnit.MINUTES);
        }
        catch (RuntimeException exception)
        {
            redisService.deleteObject(java.util.Arrays.asList(codeKey, emailLimitKey, ipLimitKey, deviceLimitKey));
            throw new ServiceException("注册验证码邮件发送失败，请稍后重试");
        }
    }

    public void validateRegisterEmailCode(String email, String emailCode)
    {
        assertInstallerRegistrationEnabled();
        if (StringUtils.isAnyBlank(email, emailCode))
        {
            throw new ServiceException("邮箱验证码必须填写");
        }
        String key = registerEmailCodeKey(email);
        String cachedCode = redisService.getCacheObject(key);
        if (StringUtils.isBlank(cachedCode))
        {
            throw new ServiceException("邮箱验证码已过期");
        }
        if (!cachedCode.equals(emailCode) || !redisService.compareAndDelete(key, emailCode))
        {
            throw new ServiceException("邮箱验证码错误");
        }
    }

    private String registerEmailCodeKey(String email)
    {
        return REGISTER_EMAIL_CODE_KEY + normalizeEmail(email);
    }

    public boolean isInstallerRegistrationEnabled()
    {
        return registrationProperties.isInstallerEnabled();
    }

    public void assertInstallerRegistrationEnabled()
    {
        if (!registrationProperties.isInstallerEnabled())
        {
            throw new ServiceException("当前未开放安装商自助注册");
        }
    }

    private void acquireRateLimit(String key, long seconds)
    {
        if (seconds > 0 && !redisService.setCacheObjectIfAbsent(key, "1", seconds, TimeUnit.SECONDS))
        {
            throw new ServiceException("请求过于频繁，请稍后再试");
        }
    }

    private String normalizeEmail(String email)
    {
        return email.trim().toLowerCase();
    }

    private String safeRateLimitValue(String value)
    {
        return StringUtils.isBlank(value) ? "unknown" : value.trim().toLowerCase();
    }
}
