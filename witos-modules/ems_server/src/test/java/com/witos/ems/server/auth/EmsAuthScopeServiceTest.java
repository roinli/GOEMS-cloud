package com.witos.ems.server.auth;

import com.witos.common.core.constant.SecurityConstants;
import com.witos.common.core.context.SecurityContextHolder;
import com.witos.system.api.domain.SysUser;
import com.witos.system.api.model.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmsAuthScopeServiceTest
{
    @AfterEach
    void clearContext()
    {
        SecurityContextHolder.remove();
    }

    @Test
    void adminRoleHasPlatformFullAccessEvenWhenUserIdIsNotOne()
    {
        SecurityContextHolder.setUserId("42");
        LoginUser loginUser = new LoginUser();
        loginUser.setRoles(Collections.singleton("admin"));
        SecurityContextHolder.set(SecurityConstants.LOGIN_USER, loginUser);

        EmsDataScope scope = new EmsAuthScopeService().currentScope();

        assertTrue(scope.isPlatformFullAccess());
        assertFalse(scope.isScopeRestricted());
    }

    @Test
    void rootRoleHasPlatformFullAccessEvenWhenUserIdIsNotOne()
    {
        SecurityContextHolder.setUserId("42");
        LoginUser loginUser = new LoginUser();
        loginUser.setRoles(Collections.singleton("root"));
        SecurityContextHolder.set(SecurityConstants.LOGIN_USER, loginUser);

        EmsDataScope scope = new EmsAuthScopeService().currentScope();

        assertTrue(scope.isPlatformFullAccess());
        assertFalse(scope.isScopeRestricted());
    }

    @Test
    void platformRootAccountHasPlatformFullAccessWithLegacyCachedLogin()
    {
        SecurityContextHolder.setUserId("42");
        SecurityContextHolder.setTenantId("9999");
        SecurityContextHolder.setUserName("root");
        LoginUser loginUser = new LoginUser();
        SysUser sysUser = new SysUser();
        sysUser.setUserName("root");
        loginUser.setSysUser(sysUser);
        SecurityContextHolder.set(SecurityConstants.LOGIN_USER, loginUser);

        EmsDataScope scope = new EmsAuthScopeService().currentScope();

        assertTrue(scope.isPlatformFullAccess());
        assertFalse(scope.isScopeRestricted());
    }
}
