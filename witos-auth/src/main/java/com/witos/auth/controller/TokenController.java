package com.witos.auth.controller;

import java.util.Map;
import javax.servlet.http.HttpServletRequest;

import com.witos.auth.form.LoginBody;
import com.witos.auth.form.RegisterBody;
import com.witos.auth.service.SysLoginService;
import com.witos.common.security.service.TokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.witos.common.core.domain.R;
import com.witos.common.core.utils.JwtUtils;
import com.witos.common.core.utils.StringUtils;
import com.witos.common.security.auth.AuthUtil;
import com.witos.common.security.utils.SecurityUtils;
import com.witos.system.api.domain.SysTenantRegister;
import com.witos.system.api.model.LoginUser;

/**
 * token 控制
 *
 * @author witos
 */
@RestController
public class TokenController
{
    @Autowired
    private TokenService tokenService;

    @Autowired
    private SysLoginService sysLoginService;

    @PostMapping("login")
    public R<?> login(@RequestBody LoginBody form)
    {
        // 用户登录
        LoginUser userInfo = sysLoginService.login(form.getUsername(), form.getPassword());
        // 获取登录token
        return R.ok(tokenService.createToken(userInfo));
    }

    @DeleteMapping("logout")
    public R<?> logout(HttpServletRequest request)
    {
        String token = SecurityUtils.getToken(request);
        if (StringUtils.isNotEmpty(token))
        {
            String username = JwtUtils.getUserName(token);
            // 删除用户缓存记录
            AuthUtil.logoutByToken(token);
            // 记录用户退出日志
            sysLoginService.logout(username);
        }
        return R.ok();
    }

    @PostMapping("refresh")
    public R<?> refresh(HttpServletRequest request)
    {
        LoginUser loginUser = tokenService.getLoginUser(request);
        if (StringUtils.isNotNull(loginUser))
        {
            // 刷新令牌有效期
            tokenService.refreshToken(loginUser);
            return R.ok();
        }
        return R.ok();
    }

    @PostMapping("register")
    public R<?> register(@RequestBody RegisterBody registerBody)
    {
        if ("INSTALLER_SELF_REGISTER".equals(registerBody.getAccountOpenType()))
        {
            sysLoginService.assertInstallerRegistrationEnabled();
            sysLoginService.validateRegisterEmailCode(registerBody.getEmail(), registerBody.getEmailCode());
            SysTenantRegister tenantRegister = new SysTenantRegister();
            tenantRegister.setCompanyName(registerBody.getCompanyName());
            tenantRegister.setUsername(registerBody.getUsername());
            tenantRegister.setPassword(registerBody.getPassword());
            tenantRegister.setPhonenumber(registerBody.getPhonenumber());
            tenantRegister.setEmail(registerBody.getEmail());
            tenantRegister.setProvince(registerBody.getProvince());
            tenantRegister.setCity(registerBody.getCity());
            sysLoginService.registerInstaller(tenantRegister);
            return R.ok();
        }
        // 用户注册
        sysLoginService.register(registerBody.getUsername(), registerBody.getPassword());
        return R.ok();
    }

    @PostMapping("register/email-code")
    public R<?> sendRegisterEmailCode(@RequestBody Map<String, String> body, HttpServletRequest request)
    {
        sysLoginService.sendRegisterEmailCode(body == null ? null : body.get("email"),
            com.witos.common.core.utils.ip.IpUtils.getIpAddr(request), request.getHeader("X-Device-Id"));
        return R.ok();
    }

    @GetMapping("register")
    public R<Map<String, Boolean>> registerStatus()
    {
        return R.ok(java.util.Collections.singletonMap("installerEnabled", sysLoginService.isInstallerRegistrationEnabled()));
    }
}
