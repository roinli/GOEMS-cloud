package com.witos.system.mapper;

import java.util.List;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.witos.common.mybatisplus.mapper.BaseMapperX;
import com.witos.system.api.domain.SysRole;
import org.apache.ibatis.annotations.Param;

/**
 * 角色表 数据层
 *
 * @author witos
 */
 public interface SysRoleMapper extends BaseMapperX<SysRole>
{
    /**
     * 根据条件分页查询角色数据
     *
     * @param role 角色信息
     * @return 角色数据集合信息
     */
    IPage<SysRole> selectRoleList(Page page,@Param("query") SysRole role);

    /**
     * 根据条件分页查询角色数据
     *
     * @param role 角色信息
     * @return 角色数据集合信息
     */
     List<SysRole> selectRoleList(@Param("query") SysRole role);

    /**
     * 根据用户ID查询角色
     *
     * @param userId 用户ID
     * @return 角色列表
     */
    @InterceptorIgnore(tenantLine = "1")
    List<SysRole> selectRolePermissionByUserId(Long userId);

    /**
     * 根据租户ID查询默认管理员角色
     *
     * @param tenantId 租户ID
     * @return 角色
     */
    @InterceptorIgnore(tenantLine = "1")
    List<SysRole> queryAdminRole(Long tenantId);

    /**
     * 查询所有角色
     *
     * @return 角色列表
     */
     List<SysRole> selectRoleAll();

    /**
     * 根据租户ID查询角色
     *
     * @param tenantId 租户ID
     * @return 角色列表
     */
    @InterceptorIgnore(tenantLine = "1")
    List<SysRole> selectRolesByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 根据用户ID和租户ID查询已分配角色
     *
     * @param userId 用户ID
     * @param tenantId 租户ID
     * @return 已分配角色列表
     */
    @InterceptorIgnore(tenantLine = "1")
    List<SysRole> selectAssignedRolesByUserIdAndTenantId(@Param("userId") Long userId,
                                                         @Param("tenantId") Long tenantId);

    /**
     * 根据用户ID获取角色选择框列表
     *
     * @param userId 用户ID
     * @return 选中角色ID列表
     */
     List<Long> selectRoleListByUserId(Long userId);

    /**
     * 通过角色ID查询角色
     *
     * @param roleId 角色ID
     * @return 角色对象信息
     */
     SysRole selectRoleById(Long roleId);

    /**
     * 根据用户ID查询角色
     *
     * @param userName 用户名
     * @return 角色列表
     */
     List<SysRole> selectRolesByUserName(String userName);

    /**
     * 校验角色名称是否唯一
     *
     * @param roleName 角色名称
     * @param tenantId 租户ID
     * @param roleId 当前角色ID
     * @return 角色信息
     */
     SysRole checkRoleNameUnique(@Param("roleName") String roleName,
                                 @Param("tenantId") Long tenantId,
                                 @Param("roleId") Long roleId);

    /**
     * 校验角色权限是否唯一
     *
     * @param roleKey 角色权限
     * @param tenantId 租户ID
     * @param roleId 当前角色ID
     * @return 角色信息
     */
     SysRole checkRoleKeyUnique(@Param("roleKey") String roleKey,
                                @Param("tenantId") Long tenantId,
                                @Param("roleId") Long roleId);

    /**
     * 修改角色信息
     *
     * @param role 角色信息
     * @return 结果
     */
     int updateRole(SysRole role);

    /**
     * 新增角色信息
     *
     * @param role 角色信息
     * @return 结果
     */
     int insertRole(SysRole role);

    /**
     * 通过角色ID删除角色
     *
     * @param roleId 角色ID
     * @return 结果
     */
     int deleteRoleById(Long roleId);

    /**
     * 批量删除角色信息
     *
     * @param roleIds 需要删除的角色ID
     * @return 结果
     */
     int deleteRoleByIds(Long[] roleIds);

    /**
     * 查询 EMS 角色模板
     *
     * @return 模板角色列表
     */
    @InterceptorIgnore(tenantLine = "1")
    List<SysRole> selectTemplateEmsRoles();

    /**
     * 批量删除角色信息-根据租户
     *
     * @param ids 需要删除的租户id
     * @return 结果
     */
    @InterceptorIgnore(tenantLine = "1")
    int deleteRoleByTenantId(Long[] ids);

    /**
     * 通过租户id查询当前租户的管理员角色
     *
     * @param tenantid 角色ID
     * @return 角色对象信息
     */
    @InterceptorIgnore(tenantLine = "1")
    SysRole selectRoleByTenant(Long tenantid);

}
