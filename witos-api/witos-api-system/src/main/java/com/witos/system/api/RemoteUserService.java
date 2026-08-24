package com.witos.system.api;

import com.witos.system.api.domain.EmsUserRolePayload;
import com.witos.system.api.domain.SysRole;
import com.witos.system.api.domain.SysUser;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import com.witos.common.core.constant.SecurityConstants;
import com.witos.common.core.constant.ServiceNameConstants;
import com.witos.common.core.domain.R;
import com.witos.system.api.factory.RemoteUserFallbackFactory;
import com.witos.system.api.model.LoginUser;

import java.util.List;

/**
 * 用户服务
 *
 * @author witos
 */
@FeignClient(contextId = "remoteUserService", value = ServiceNameConstants.SYSTEM_SERVICE, fallbackFactory = RemoteUserFallbackFactory.class)
public interface RemoteUserService
{
    /**
     * 通过用户名查询用户信息
     *
     * @param username 用户名
     * @param source 请求来源
     * @return 结果
     */
    @GetMapping("/user/info/{username}")
    R<LoginUser> getUserInfo(@PathVariable("username") String username, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    /**
     * 注册用户信息
     *
     * @param sysUser 用户信息
     * @param source 请求来源
     * @return 结果
     */
    @PostMapping("/user/register")
    R<Boolean> registerUserInfo(@RequestBody SysUser sysUser, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    @GetMapping("/user/ems/{userId}")
    R<SysUser> getEmsUser(@PathVariable("userId") Long userId, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    @PostMapping("/user/ems/check-username")
    R<Boolean> checkEmsUserNameUnique(@RequestBody SysUser sysUser, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    @PostMapping("/user/ems/check-phone")
    R<Boolean> checkEmsPhoneUnique(@RequestBody SysUser sysUser, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    @PostMapping("/user/ems/check-email")
    R<Boolean> checkEmsEmailUnique(@RequestBody SysUser sysUser, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    @PostMapping("/user/ems")
    R<SysUser> addEmsUser(@RequestBody SysUser sysUser, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    @PutMapping("/user/ems")
    R<Boolean> updateEmsUser(@RequestBody SysUser sysUser, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    @DeleteMapping("/user/ems/{userId}")
    R<Boolean> deleteEmsUser(@PathVariable("userId") Long userId, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    @GetMapping("/user/ems/default-dept/{currentUserId}")
    R<Long> getEmsDefaultDeptId(@PathVariable("currentUserId") Long currentUserId, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    @GetMapping("/user/ems/roles")
    R<List<SysRole>> listEmsRoles(@RequestParam("tenantId") Long tenantId,
                                  @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    @GetMapping("/user/ems/{userId}/roles")
    R<List<SysRole>> listEmsUserRoles(@PathVariable("userId") Long userId,
                                      @RequestParam("tenantId") Long tenantId,
                                      @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    @PostMapping("/user/ems/roles")
    R<Boolean> replaceEmsUserRoles(@RequestBody EmsUserRolePayload payload, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}
