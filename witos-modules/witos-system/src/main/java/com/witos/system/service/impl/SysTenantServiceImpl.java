package com.witos.system.service.impl;

import java.util.*;
import java.util.stream.Collectors;

import com.witos.common.core.constant.CacheConstants;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.common.core.web.domain.AjaxResult;
import com.witos.common.message.mail.EmailUtil;
import com.witos.common.message.sms.SmsUtil;
import com.witos.common.mybatisplus.util.TenantUtils;
import com.witos.common.redis.service.RedisService;
import com.witos.common.security.utils.SecurityUtils;
import com.witos.system.api.domain.*;
import com.witos.system.api.model.LoginUser;
import com.witos.system.domain.*;
import com.witos.system.mapper.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.witos.system.service.ISysTenantService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.witos.common.core.text.Convert;
import com.witos.common.core.utils.ServletUtils;
import com.witos.common.mybatisplus.constant.MybatisPageConstants;
import org.springframework.transaction.annotation.Transactional;


/**
 * 租户管理Service业务层处理
 *
 * @author witos
 * @date 2022-04-11
 */
@Slf4j
@Service
public class SysTenantServiceImpl implements ISysTenantService
{
    private static final String INSTALLER_ADMIN_ROLE_KEY = "ems_installer_admin";
    private static final String DEFAULT_INSTALLER_ADMIN_PASSWORD = "admin123456";
    private static final Long DEFAULT_INSTALLER_PACKAGE_ID = 104L;

    @Autowired
    private SysTenantMapper sysTenantMapper;

    @Autowired
    private SysTenantPackageMapper sysTenantPackageMapper;

    @Autowired
    private SysDeptMapper deptMapper;

    @Autowired
    private SysPostMapper sysPostMapper;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysRoleMenuMapper sysRoleMenuMapper;

    @Autowired
    private SysRoleDeptMapper sysRoleDeptMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SysUserRoleMapper userRoleMapper;

    @Autowired
    private SysUserPostMapper userPostMapper;

    @Autowired
    private SysEmsUserProfileMapper sysEmsUserProfileMapper;

    @Autowired
    EmailUtil emailUtil;

    @Autowired
    SmsUtil smsUtil;

    @Autowired
    private RedisService redisService;


    /**
     * 查询租户管理
     *
     * @param id 租户管理主键
     * @return 租户管理
     */
    @Override
    public SysTenant selectSysTenantById(Long id)
    {
        return sysTenantMapper.selectById(id);
    }

    /**
     * 查询租户管理列表-分页
     *
     * @param sysTenant 租户管理
     * @return 租户管理
     */
    @Override
    public IPage<SysTenant> selectSysTenantPage(SysTenant sysTenant)
    {
        Page mpPage =new Page(Convert.toLong(ServletUtils.getParameterToInt(MybatisPageConstants.PAGE_NUM),1L)
                ,Convert.toLong(ServletUtils.getParameterToInt(MybatisPageConstants.PAGE_SIZE),10L));
        return sysTenantMapper.selectSysTenantList(mpPage,sysTenant);
    }
    /**
     * 查询租户管理列表
     *
     * @param sysTenant 租户管理
     * @return 租户管理
     */
    @Override
    public List<SysTenant> selectSysTenantList(SysTenant sysTenant) {return sysTenantMapper.selectSysTenantList(sysTenant);}

    /**
     * 新增租户管理
     *
     * @param sysTenant 租户管理
     * @return 结果
     */

    @Override
    @Transactional(rollbackFor = Exception.class)

    public AjaxResult insertSysTenant(SysTenant sysTenant)
    {
        //新增租户开始
        if (StringUtils.isEmpty(sysTenant.getId())){
            return AjaxResult.error("租户编码为空,请重新设置!");
        }
        //先判断租户编码是否存在
        if (9999L == sysTenant.getId()){
            //优化租户列表里不存在9999的超级管理员租户导致查出结果为0跳过校验
            return AjaxResult.error("租户编码已存在,请重新设置!");
        }
        Long tenantcount = sysTenantMapper.selectCount("id",sysTenant.getId());
        if (tenantcount > 0)
        {
            return AjaxResult.error("租户编码已存在,请重新设置!");
        }
        if (StringUtils.isEmpty(sysTenant.getUserName())){
            return AjaxResult.error("管理员账号为空,请重新设置!");
        }
        //先判断租户管理员设置的账号是否存在
        SysUser usercount = userMapper.checkUserNameUnique(sysTenant.getUserName());
        if (!(usercount == null))
        {
            return AjaxResult.error("用户名已存在,请重新设置!");
        }
        bindDefaultInstallerPackage(sysTenant);
        //创建租户
        sysTenantMapper.insert(sysTenant);
        Long installerRoleId = initTenantRoles(sysTenant);
        if (installerRoleId == null)
        {
            throw new ServiceException("系统默认安装商管理员角色未初始化");
        }
        final String[] passwordHolder = new String[1];
        //租户创建完成后 开始创建相关基础数据
        TenantUtils.execute(sysTenant.getId(), () -> {
            //创建默认部门--部门默认名称以租户名称
            Long deptid = createDept(sysTenant);
            //创建默认岗位--岗位默认为董事长
            Long postid = createPost(sysTenant.getUserName());
            //创建默认账号
            passwordHolder[0] = createUser(sysTenant,deptid,postid,installerRoleId);
        });
        List<SysRole> installerRoles = sysRoleMapper.queryAdminRole(sysTenant.getId());
        SysRole installerRole = installerRoles == null || installerRoles.isEmpty() ? null : installerRoles.get(0);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("tenantId", sysTenant.getId());
        result.put("tenantName", sysTenant.getTenantName());
        result.put("userName", sysTenant.getUserName());
        result.put("roleId", installerRole == null ? installerRoleId : installerRole.getRoleId());
        result.put("roleName", installerRole == null ? "安装商管理员" : installerRole.getRoleName());
        result.put("roleKey", installerRole == null ? INSTALLER_ADMIN_ROLE_KEY : installerRole.getRoleKey());
        result.put("password", passwordHolder[0]);
        return AjaxResult.success("租户创建成功!", result);
    }

    private void bindDefaultInstallerPackage(SysTenant sysTenant)
    {
        if (sysTenant.getTenantPackage() == null)
        {
            sysTenant.setTenantPackage(DEFAULT_INSTALLER_PACKAGE_ID);
        }
        SysTenantPackage tenantPackage = sysTenantPackageMapper.selectById(sysTenant.getTenantPackage());
        if (tenantPackage == null || StringUtils.isEmpty(tenantPackage.getMenuIds()))
        {
            throw new ServiceException("注册安装商套餐未初始化");
        }
    }

    private Long initTenantRoles(SysTenant sysTenant)
    {
        List<SysRole> templateRoles = sysRoleMapper.selectTemplateEmsRoles();
        if (templateRoles == null || templateRoles.isEmpty())
        {
            return null;
        }
        Set<Long> packageMenuIds = resolvePackageMenuIds(sysTenant);
        Long installerRoleId = null;
        for (SysRole templateRole : templateRoles)
        {
            SysRole tenantRole = new SysRole();
            tenantRole.setTenantId(sysTenant.getId());
            tenantRole.setRoleName(templateRole.getRoleName());
            tenantRole.setRoleKey(templateRole.getRoleKey());
            tenantRole.setRoleSort(templateRole.getRoleSort());
            tenantRole.setDataScope(templateRole.getDataScope());
            tenantRole.setMenuCheckStrictly(templateRole.isMenuCheckStrictly());
            tenantRole.setDeptCheckStrictly(templateRole.isDeptCheckStrictly());
            tenantRole.setAdminRole(templateRole.isAdminRole());
            tenantRole.setStatus(templateRole.getStatus());
            tenantRole.setDelFlag(templateRole.getDelFlag());
            tenantRole.setRemark(templateRole.getRemark());
            tenantRole.setCreateBy(sysTenant.getUserName());
            sysRoleMapper.insertRole(tenantRole);
            copyRoleMenus(templateRole.getRoleId(), tenantRole.getRoleId(), sysTenant.getId(), templateRole.getRoleKey(), packageMenuIds);
            if (INSTALLER_ADMIN_ROLE_KEY.equals(templateRole.getRoleKey()))
            {
                installerRoleId = tenantRole.getRoleId();
            }
        }
        return installerRoleId;
    }

    private Set<Long> resolvePackageMenuIds(SysTenant sysTenant)
    {
        if (sysTenant.getTenantPackage() == null)
        {
            return Collections.emptySet();
        }
        SysTenantPackage tenantPackage = sysTenantPackageMapper.selectById(sysTenant.getTenantPackage());
        if (tenantPackage == null || StringUtils.isEmpty(tenantPackage.getMenuIds()))
        {
            return Collections.emptySet();
        }
        return Arrays.stream(tenantPackage.getMenuIds().split(","))
                .filter(StringUtils::isNotEmpty)
                .map(String::trim)
                .map(Long::valueOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void copyRoleMenus(Long templateRoleId, Long tenantRoleId, Long tenantId, String templateRoleKey, Set<Long> packageMenuIds)
    {
        List<Long> templateMenuIds = sysRoleMenuMapper.selectTemplateMenuIdsByRoleId(templateRoleId);
        if (templateMenuIds == null || templateMenuIds.isEmpty())
        {
            if (INSTALLER_ADMIN_ROLE_KEY.equals(templateRoleKey))
            {
                throw new ServiceException("系统默认安装商管理员菜单权限未初始化");
            }
            return;
        }
        List<SysRoleMenu> roleMenus = new ArrayList<>();
        for (Long menuId : templateMenuIds)
        {
            if (!packageMenuIds.isEmpty() && !packageMenuIds.contains(menuId))
            {
                continue;
            }
            SysRoleMenu roleMenu = new SysRoleMenu();
            roleMenu.setRoleId(tenantRoleId);
            roleMenu.setMenuId(menuId);
            roleMenu.setTenantId(tenantId);
            roleMenus.add(roleMenu);
        }
        if (INSTALLER_ADMIN_ROLE_KEY.equals(templateRoleKey) && roleMenus.isEmpty())
        {
            throw new ServiceException("系统默认安装商管理员菜单权限未初始化");
        }
        if (!roleMenus.isEmpty())
        {
            sysRoleMenuMapper.batchRoleMenu(roleMenus);
        }
    }

    private String createUser(SysTenant sysTenant,Long deptId,Long postid,Long roleid) {
        SysUser user = new SysUser();
        user.setDeptId(deptId).setUserName(sysTenant.getUserName()).setNickName(sysTenant.getTenantName())
                .setUserType("00")//用户类型 00 表示各管理员账号，不允许租户修改删除 其他账号为10
                .setEmail(sysTenant.getUserEmail()).setPhonenumber(sysTenant.getUserPhone()).setRemark("安装商管理员");
        user.setTenantId(sysTenant.getId());
        user.setStatus("0").setDelFlag("0");
        String rawPassword = StringUtils.isEmpty(sysTenant.getAdminPassword()) ? DEFAULT_INSTALLER_ADMIN_PASSWORD : sysTenant.getAdminPassword();
        String password = SecurityUtils.encryptPassword(rawPassword);
        user.setPassword(password);
        userMapper.insert(user);
        userPostMapper.insert(new SysUserPost().setUserId(user.getUserId()).setPostId(postid).setTenantId(sysTenant.getId()));
        userRoleMapper.insert(new SysUserRole().setRoleId(roleid).setUserId(user.getUserId()).setTenantId(sysTenant.getId()));
        EmsUserProfile profile = new EmsUserProfile();
        profile.setTenantId(sysTenant.getId());
        profile.setUserId(user.getUserId());
        profile.setPrimaryCompanyId(0L);
        profile.setIsDefaultInstallerAdmin("1");
        profile.setStatus("0");
        profile.setCreateBy(sysTenant.getUserName());
        profile.setRemark("默认安装商管理员");
        sysEmsUserProfileMapper.insert(profile);
        return rawPassword;
    }

    private Long createPost(String username) {
        SysPost post = new SysPost();
        post.setPostCode("ceo").setPostName("董事长").setPostSort("1");
        post.setTenantId(SecurityUtils.getTenantId());
        post.setCreateBy(username);
        sysPostMapper.insert(post);
        return post.getPostId();
    }

    private Long createDept(SysTenant sysTenant) {
        // 创建部门
        SysDept dept = new SysDept();
        dept.setParentId(0L).setAncestors("0").setDeptName(sysTenant.getTenantName()).setOrderNum(0)
                .setLeader(sysTenant.getTenantName()+"管理员").setPhone(sysTenant.getUserPhone()).setEmail(sysTenant.getUserEmail());
        dept.setTenantId(sysTenant.getId());
        deptMapper.insert(dept);
        return dept.getDeptId();
    }


    /**
     * 修改租户管理
     *
     * @param sysTenant 租户管理
     * @return 结果
     */
    @Override
    public int updateSysTenant(SysTenant sysTenant)
    {
        //判断最新的租户套餐是否改变，若变更则刷新当前租户在线会话
        SysTenant t_sysTenant = sysTenantMapper.selectById(sysTenant.getId());
        if(sysTenant.getTenantPackage() != null && !sysTenant.getTenantPackage().equals(t_sysTenant.getTenantPackage()))
        {
            Collection<String> keys = redisService.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
            for (String key : keys)
            {
                LoginUser onlineUser = redisService.getCacheObject(key);
                if(onlineUser != null
                        && onlineUser.getSysUser() != null
                        && onlineUser.getSysUser().getTenantId() != null
                        && onlineUser.getSysUser().getTenantId().equals(sysTenant.getId()))
                {
                    redisService.deleteObject(key);
                }
            }
        }
        return sysTenantMapper.updateById(sysTenant);
    }

    /**
     * 批量删除租户管理
     *
     * @param ids 需要删除的租户管理主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteSysTenantByIds(Long[] ids)
    {
        //优化删除逻辑
        //1.先删租户
        int tenantres = sysTenantMapper.deleteSysTenantByIds(ids);
        if(tenantres>0){
            //下面才会进行子模块数据的删除
            //部门模块
            deptMapper.deleteDeptByTenantId(ids);
            //职位模块
            sysPostMapper.deletePostByTenantId(ids);
            //权限
            sysRoleMapper.deleteRoleByTenantId(ids);
            sysRoleMenuMapper.deleteRoleMenuByTenantIds(ids);
            sysRoleDeptMapper.deleteRoleDeptByTenantId(ids);
            //账号
            userMapper.deleteUserByTenantId(ids);
            userRoleMapper.deleteUserRoleByTenantId(ids);
            userPostMapper.deleteUserPostByTenantId(ids);
            return 1;
        }else {
            throw new ServiceException("当前租户已被删除不存在！");
        }
    }

    /**
     * 删除租户管理信息
     *
     * @param id 租户管理主键
     * @return 结果
     */
    @Override
    public int deleteSysTenantById(Long id)
    {
        return sysTenantMapper.deleteById(id);
    }
}
