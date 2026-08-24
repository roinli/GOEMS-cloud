package com.witos.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.common.core.domain.R;
import com.witos.common.core.utils.StringUtils;
import com.witos.common.security.annotation.InnerAuth;
import com.witos.system.api.domain.EmsUserRolePayload;
import com.witos.system.api.domain.SysDept;
import com.witos.system.api.domain.SysRole;
import com.witos.system.api.domain.SysUser;
import com.witos.system.domain.SysUserRole;
import com.witos.system.mapper.SysDeptMapper;
import com.witos.system.mapper.SysUserRoleMapper;
import com.witos.system.service.ISysRoleService;
import com.witos.system.service.ISysUserService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/user/ems")
public class SysEmsUserController
{
    @Resource
    private ISysUserService userService;

    @Resource
    private ISysRoleService roleService;

    @Resource
    private SysDeptMapper deptMapper;

    @Resource
    private SysUserRoleMapper userRoleMapper;

    @InnerAuth
    @GetMapping("/{userId}")
    public R<SysUser> getUser(@PathVariable("userId") Long userId)
    {
        return R.ok(userService.selectUserById(userId));
    }

    @InnerAuth
    @PostMapping("/check-username")
    public R<Boolean> checkUserNameUnique(@RequestBody SysUser user)
    {
        return R.ok(userService.checkUserNameUnique(user));
    }

    @InnerAuth
    @PostMapping("/check-phone")
    public R<Boolean> checkPhoneUnique(@RequestBody SysUser user)
    {
        if (user == null || StringUtils.isEmpty(user.getPhonenumber()))
        {
            return R.ok(true);
        }
        return R.ok(userService.checkPhoneUnique(user));
    }

    @InnerAuth
    @PostMapping("/check-email")
    public R<Boolean> checkEmailUnique(@RequestBody SysUser user)
    {
        if (user == null || StringUtils.isEmpty(user.getEmail()))
        {
            return R.ok(true);
        }
        return R.ok(userService.checkEmailUnique(user));
    }

    @InnerAuth
    @PostMapping
    public R<SysUser> addUser(@RequestBody SysUser user)
    {
        userService.insertUser(user);
        return R.ok(user);
    }

    @InnerAuth
    @PutMapping
    public R<Boolean> updateUser(@RequestBody SysUser user)
    {
        return R.ok(userService.updateUserProfile(user) > 0);
    }

    @InnerAuth
    @DeleteMapping("/{userId}")
    public R<Boolean> deleteUser(@PathVariable("userId") Long userId)
    {
        return R.ok(userService.deleteUserById(userId) > 0);
    }

    @InnerAuth
    @GetMapping("/default-dept/{currentUserId}")
    public R<Long> defaultDeptId(@PathVariable("currentUserId") Long currentUserId)
    {
        if (currentUserId != null)
        {
            SysUser currentUser = userService.selectUserById(currentUserId);
            if (currentUser != null && currentUser.getDeptId() != null)
            {
                return R.ok(currentUser.getDeptId());
            }
        }
        SysDept dept = deptMapper.selectOne(new LambdaQueryWrapper<SysDept>()
                .eq(SysDept::getDelFlag, "0")
                .orderByAsc(SysDept::getDeptId)
                .last("limit 1"));
        return R.ok(dept == null ? 0L : dept.getDeptId());
    }

    @InnerAuth
    @GetMapping("/roles")
    public R<List<SysRole>> roles(@RequestParam("tenantId") Long tenantId)
    {
        return R.ok(roleService.selectRolesByTenantId(tenantId));
    }

    @InnerAuth
    @GetMapping("/{userId}/roles")
    public R<List<SysRole>> userRoles(@PathVariable("userId") Long userId,
                                      @RequestParam("tenantId") Long tenantId)
    {
        List<SysRole> roles = roleService.selectAssignedRolesByUserIdAndTenantId(userId, tenantId);
        roles.forEach(role -> role.setFlag(true));
        return R.ok(roles);
    }

    @InnerAuth
    @PostMapping("/roles")
    @Transactional(rollbackFor = Exception.class)
    public R<Boolean> replaceUserRoles(@RequestBody EmsUserRolePayload payload)
    {
        userRoleMapper.deleteUserRoleByUserId(payload.getUserId());
        List<Long> roleIds = payload.getRoleIds();
        if (roleIds == null || roleIds.isEmpty())
        {
            return R.ok(true);
        }
        List<SysUserRole> rows = new ArrayList<SysUserRole>();
        for (Long roleId : roleIds)
        {
            SysUserRole row = new SysUserRole();
            row.setTenantId(payload.getTenantId());
            row.setUserId(payload.getUserId());
            row.setRoleId(roleId);
            rows.add(row);
        }
        return R.ok(userRoleMapper.batchUserRole(rows) > 0);
    }
}
