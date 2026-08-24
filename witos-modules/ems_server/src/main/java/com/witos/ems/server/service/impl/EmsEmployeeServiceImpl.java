package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.witos.common.core.constant.SecurityConstants;
import com.witos.common.core.constant.UserConstants;
import com.witos.common.core.domain.R;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.common.security.utils.SecurityUtils;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.domain.entity.EmsUserProfile;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsEmployeeMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.ems.server.mapper.EmsUserProfileMapper;
import com.witos.ems.server.service.EmsEmployeeRoleService;
import com.witos.ems.server.service.EmsEmployeeScopeService;
import com.witos.ems.server.service.EmsEmployeeService;
import com.witos.ems.server.support.EmsPageSupport;
import com.witos.ems.server.support.EmsRequestSupport;
import com.witos.system.api.RemoteUserService;
import com.witos.system.api.domain.SysRole;
import com.witos.system.api.domain.SysUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EmsEmployeeServiceImpl implements EmsEmployeeService
{
    private static final String DEFAULT_EMPLOYEE_PASSWORD = "123456";

    @Resource
    private EmsEmployeeMapper employeeMapper;

    @Resource
    private EmsUserProfileMapper userProfileMapper;

    @Resource
    private EmsCompanyMapper companyMapper;

    @Resource
    private EmsStationMapper stationMapper;

    @Resource
    private EmsAuthScopeService authScopeService;

    @Resource
    private RemoteUserService remoteUserService;

    @Resource
    private EmsEmployeeRoleService employeeRoleService;

    @Resource
    private EmsEmployeeScopeService employeeScopeService;

    @Override
    public IPage<Map<String, Object>> list(Map<String, String> query)
    {
        IPage<Map<String, Object>> page = employeeMapper.selectEmployeePage(EmsPageSupport.page(), queryMap(query), authScopeService.currentScope());
        page.getRecords().forEach(this::enrichScopeSummary);
        return page;
    }

    @Override
    public List<Map<String, Object>> listAll(Map<String, String> query)
    {
        List<Map<String, Object>> rows = employeeMapper.selectEmployeeList(queryMap(query), authScopeService.currentScope());
        rows.forEach(this::enrichScopeSummary);
        return rows;
    }

    @Override
    public Map<String, Object> get(Long userId)
    {
        Map<String, Object> detail = employeeMapper.selectEmployeeDetail(userId, authScopeService.currentScope());
        if (detail == null || detail.isEmpty())
        {
            return new LinkedHashMap<String, Object>();
        }
        Long tenantId = EmsRequestSupport.asLong(detail.get("tenantId"));
        detail.put("roleIds", employeeRoleService.listUserRoleIds(tenantId, userId));
        detail.put("roleNames", employeeRoleService.listUserRoleNames(tenantId, userId));
        employeeScopeService.fillScopeDetail(detail, userId);
        enrichScopeSummary(detail);
        return detail;
    }

    @Override
    public List<Map<String, Object>> roleOptions()
    {
        return employeeRoleService.listAssignableRoles(EmsRequestSupport.currentTenantId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Map<String, Object> body)
    {
        Long userId = EmsRequestSupport.coalesceId(body, "userId", "id");
        Long companyId = EmsRequestSupport.requiredLong(body, "companyId", "公司不能为空");
        String userName = EmsRequestSupport.stringValue(body.get("userName"));
        if (StringUtils.isEmpty(userName))
        {
            throw new ServiceException("用户名不能为空");
        }
        String phone = EmsRequestSupport.stringValue(body.get("phonenumber"));
        if (StringUtils.isEmpty(phone))
        {
            throw new ServiceException("手机号不能为空");
        }
        String email = EmsRequestSupport.stringValue(body.get("email"));

        String status = EmsRequestSupport.defaultString(body.get("status"), UserConstants.NORMAL);
        String remark = EmsRequestSupport.stringValue(body.get("remark"));
        List<Long> roleIds = EmsRequestSupport.asLongList(body.get("roleIds"));
        Map<String, Object> companyDetail = validatePrimaryCompany(companyId);
        Long tenantId = EmsRequestSupport.asLong(companyDetail.get("tenantId"));
        if (userId != null)
        {
            Map<String, Object> existing = employeeMapper.selectEmployeeDetail(userId, authScopeService.currentScope());
            if (existing == null || existing.isEmpty())
            {
                throw new ServiceException("员工不存在或超出当前授权范围");
            }
            Long existingTenantId = EmsRequestSupport.asLong(existing.get("tenantId"));
            if (existingTenantId != null && !existingTenantId.equals(tenantId))
            {
                throw new ServiceException("不能将员工迁移到其他租户");
            }
        }
        validateRoleTenant(roleIds, tenantId);
        validateScopePayload(tenantId, companyId, body);

        if (userId == null)
        {
            validateUserNameForCreate(userName);
            validatePhoneForCreate(phone);
            validateEmailForCreate(email);
            SysUser user = requireRemote(remoteUserService.addEmsUser(
                    buildCreateUser(tenantId, body, userName, phone, email, status, remark), SecurityConstants.INNER));
            userId = user.getUserId();
            insertUserProfile(tenantId, userId, companyId, status, remark);
        }
        else
        {
            validateUserNameForUpdate(userId, userName);
            validatePhoneForUpdate(userId, phone);
            validateEmailForUpdate(userId, email);
            updateUser(userId, body, userName, phone, email, status, remark);
            updateUserProfile(userId, companyId, status, remark);
        }

        employeeRoleService.replaceUserRoles(tenantId, userId, roleIds);
        employeeScopeService.replaceUserScopes(tenantId, userId, companyId, body);
        return get(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean remove(Long userId)
    {
        Map<String, Object> detail = employeeMapper.selectEmployeeDetail(userId, authScopeService.currentScope());
        if (detail == null || detail.isEmpty())
        {
            return false;
        }
        Long tenantId = EmsRequestSupport.asLong(detail.get("tenantId"));
        employeeScopeService.removeUserScopes(tenantId, userId);
        userProfileMapper.delete(new LambdaQueryWrapper<EmsUserProfile>().eq(EmsUserProfile::getUserId, userId));
        employeeRoleService.removeUserRoles(tenantId, userId);
        return Boolean.TRUE.equals(requireRemote(remoteUserService.deleteEmsUser(userId, SecurityConstants.INNER)));
    }

    @Override
    public List<Map<String, Object>> deptTree(Long companyId)
    {
        List<Map<String, Object>> companies = companyMapper.selectCompanyList(new LinkedHashMap<String, Object>(), authScopeService.currentScope());
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> company : companies)
        {
            Long rowCompanyId = EmsRequestSupport.asLong(company.get("companyId"));
            if (companyId != null && !companyId.equals(rowCompanyId))
            {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", rowCompanyId);
            row.put("label", company.get("companyName"));
            row.put("children", new ArrayList<Object>());
            rows.add(row);
        }
        return rows;
    }

    private void validateUserNameForCreate(String userName)
    {
        SysUser user = new SysUser();
        user.setUserName(userName);
        if (!Boolean.TRUE.equals(requireRemote(remoteUserService.checkEmsUserNameUnique(user, SecurityConstants.INNER))))
        {
            throw new ServiceException("该用户名已存在");
        }
    }

    private void validateUserNameForUpdate(Long userId, String userName)
    {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setUserName(userName);
        if (!Boolean.TRUE.equals(requireRemote(remoteUserService.checkEmsUserNameUnique(user, SecurityConstants.INNER))))
        {
            throw new ServiceException("该用户名已存在");
        }
    }

    private void validatePhoneForCreate(String phone)
    {
        SysUser user = new SysUser();
        user.setPhonenumber(phone);
        if (!Boolean.TRUE.equals(requireRemote(remoteUserService.checkEmsPhoneUnique(user, SecurityConstants.INNER))))
        {
            throw new ServiceException("该手机号已存在");
        }
    }

    private void validatePhoneForUpdate(Long userId, String phone)
    {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setPhonenumber(phone);
        if (!Boolean.TRUE.equals(requireRemote(remoteUserService.checkEmsPhoneUnique(user, SecurityConstants.INNER))))
        {
            throw new ServiceException("该手机号已存在");
        }
    }

    private void validateEmailForCreate(String email)
    {
        if (StringUtils.isEmpty(email))
        {
            return;
        }
        SysUser user = new SysUser();
        user.setEmail(email);
        if (!Boolean.TRUE.equals(requireRemote(remoteUserService.checkEmsEmailUnique(user, SecurityConstants.INNER))))
        {
            throw new ServiceException("该电子邮箱已存在");
        }
    }

    private void validateEmailForUpdate(Long userId, String email)
    {
        if (StringUtils.isEmpty(email))
        {
            return;
        }
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setEmail(email);
        if (!Boolean.TRUE.equals(requireRemote(remoteUserService.checkEmsEmailUnique(user, SecurityConstants.INNER))))
        {
            throw new ServiceException("该电子邮箱已存在");
        }
    }

    private SysUser buildCreateUser(Long tenantId, Map<String, Object> body, String userName, String phone,
                                    String email, String status, String remark)
    {
        SysUser user = new SysUser();
        user.setTenantId(tenantId);
        user.setDeptId(resolveDefaultDeptId());
        user.setUserName(userName);
        user.setNickName(defaultNickName(body, userName));
        user.setEmail(email);
        user.setPhonenumber(phone);
        user.setPassword(SecurityUtils.encryptPassword(DEFAULT_EMPLOYEE_PASSWORD));
        user.setStatus(status);
        user.setRemark(remark);
        user.setUserType("00");
        user.setCreateBy(EmsRequestSupport.currentUsername());
        return user;
    }

    private void updateUser(Long userId, Map<String, Object> body, String userName, String phone, String email, String status, String remark)
    {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setUserName(userName);
        user.setNickName(defaultNickName(body, userName));
        user.setEmail(email);
        user.setPhonenumber(phone);
        user.setStatus(status);
        user.setRemark(remark);
        user.setUpdateBy(EmsRequestSupport.currentUsername());
        requireRemote(remoteUserService.updateEmsUser(user, SecurityConstants.INNER));
    }

    private void insertUserProfile(Long tenantId, Long userId, Long companyId, String status, String remark)
    {
        EmsUserProfile profile = new EmsUserProfile();
        profile.setTenantId(tenantId);
        profile.setUserId(userId);
        profile.setPrimaryCompanyId(companyId);
        profile.setIsDefaultInstallerAdmin("0");
        profile.setStatus(status);
        profile.setCreateBy(EmsRequestSupport.currentUsername());
        profile.setRemark(remark);
        userProfileMapper.insert(profile);
    }

    private void updateUserProfile(Long userId, Long companyId, String status, String remark)
    {
        EmsUserProfile profile = userProfileMapper.selectOne(new LambdaQueryWrapper<EmsUserProfile>()
                .eq(EmsUserProfile::getUserId, userId));
        if (profile == null)
        {
            Map<String, Object> company = validatePrimaryCompany(companyId);
            insertUserProfile(EmsRequestSupport.asLong(company.get("tenantId")), userId, companyId, status, remark);
            return;
        }
        profile.setPrimaryCompanyId(companyId);
        profile.setStatus(status);
        profile.setRemark(remark);
        profile.setUpdateBy(EmsRequestSupport.currentUsername());
        userProfileMapper.updateById(profile);
    }

    private Long resolveDefaultDeptId()
    {
        Long currentUserId = SecurityUtils.getUserId();
        if (currentUserId != null)
        {
            return requireRemote(remoteUserService.getEmsDefaultDeptId(currentUserId, SecurityConstants.INNER));
        }
        return 0L;
    }

    private String defaultNickName(Map<String, Object> body, String defaultValue)
    {
        String nickName = EmsRequestSupport.stringValue(body.get("nickName"));
        return StringUtils.isEmpty(nickName) ? defaultValue : nickName;
    }

    private Map<String, Object> queryMap(Map<String, String> query)
    {
        return query == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(query);
    }

    private Map<String, Object> validatePrimaryCompany(Long companyId)
    {
        Map<String, Object> companyDetail = companyMapper.selectCompanyDetail(companyId, authScopeService.currentScope());
        if (companyDetail == null || companyDetail.isEmpty())
        {
            throw new ServiceException("主公司超出当前授权范围");
        }
        return companyDetail;
    }

    private void validateScopePayload(Long tenantId, Long companyId, Map<String, Object> body)
    {
        String scopeMode = EmsRequestSupport.defaultString(body.get("scopeMode"), "COMPANY");
        if ("STATION".equalsIgnoreCase(scopeMode))
        {
            List<Long> stationIds = EmsRequestSupport.asLongList(body.get("scopeStationIds"));
            Long stationId = EmsRequestSupport.asLong(body.get("stationId"));
            if (stationIds.isEmpty() && stationId != null)
            {
                stationIds.add(stationId);
            }
            for (Long item : stationIds)
            {
                Map<String, Object> stationDetail = stationMapper.selectStationDetail(item, authScopeService.currentScope());
                if (stationDetail == null || stationDetail.isEmpty())
                {
                    throw new ServiceException("授权电站超出当前授权范围");
                }
                if (!tenantId.equals(EmsRequestSupport.asLong(stationDetail.get("tenantId"))))
                {
                    throw new ServiceException("不能授权其他租户的电站");
                }
            }
            return;
        }

        List<Long> companyIds = EmsRequestSupport.asLongList(body.get("scopeCompanyIds"));
        if (companyIds.isEmpty())
        {
            companyIds.add(companyId);
        }
        for (Long item : companyIds)
        {
            Map<String, Object> companyDetail = companyMapper.selectCompanyDetail(item, authScopeService.currentScope());
            if (companyDetail == null || companyDetail.isEmpty())
            {
                throw new ServiceException("授权公司超出当前授权范围");
            }
            if (!tenantId.equals(EmsRequestSupport.asLong(companyDetail.get("tenantId"))))
            {
                throw new ServiceException("不能授权其他租户的公司");
            }
        }
    }

    private void validateRoleTenant(List<Long> roleIds, Long companyTenantId)
    {
        if (roleIds == null || roleIds.isEmpty())
        {
            return;
        }
        List<SysRole> allRoles = requireRemote(remoteUserService.listEmsRoles(companyTenantId, SecurityConstants.INNER));
        for (Long roleId : roleIds)
        {
            SysRole matched = null;
            for (SysRole role : allRoles)
            {
                if (role != null && roleId.equals(role.getRoleId()))
                {
                    matched = role;
                    break;
                }
            }
            if (matched == null)
            {
                throw new ServiceException("存在非法角色或角色超出当前授权范围");
            }
            if (companyTenantId != null && matched.getTenantId() != null && !companyTenantId.equals(matched.getTenantId()))
            {
                throw new ServiceException("不能选择跨租户角色");
            }
        }
    }

    private void enrichScopeSummary(Map<String, Object> row)
    {
        if (row == null || row.isEmpty())
        {
            return;
        }
        Long userId = EmsRequestSupport.asLong(row.get("userId"));
        if (userId == null)
        {
            return;
        }
        employeeScopeService.fillScopeDetail(row, userId);
        String scopeMode = EmsRequestSupport.defaultString(row.get("scopeMode"), "COMPANY");
        List<Long> companyIds = EmsRequestSupport.asLongList(row.get("scopeCompanyIds"));
        List<Long> stationIds = EmsRequestSupport.asLongList(row.get("scopeStationIds"));
        if ("STATION".equalsIgnoreCase(scopeMode))
        {
            row.put("scopeSummary", "电站 " + stationIds.size() + " 个");
            return;
        }
        row.put("scopeSummary", "公司 " + companyIds.size() + " 个");
    }

    private <T> T requireRemote(R<T> result)
    {
        if (result == null || R.isError(result))
        {
            throw new ServiceException(result == null || StringUtils.isEmpty(result.getMsg()) ? "系统服务调用失败" : result.getMsg());
        }
        return result.getData();
    }
}
