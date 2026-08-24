package com.witos.system.service.impl;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.witos.common.core.constant.CacheConstants;
import com.witos.common.core.text.Convert;
import com.witos.common.core.utils.StringUtils;
import com.witos.common.core.web.domain.AjaxResult;
import com.witos.common.redis.service.RedisService;
import com.witos.system.api.domain.SysRole;
import com.witos.system.api.model.LoginUser;
import com.witos.system.domain.SysRoleMenu;
import com.witos.system.domain.SysSimplePackage;
import com.witos.system.domain.SysTenant;
import com.witos.system.mapper.SysRoleMapper;
import com.witos.system.mapper.SysRoleMenuMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.witos.system.mapper.SysTenantPackageMapper;
import com.witos.system.domain.SysTenantPackage;
import com.witos.system.service.ISysTenantPackageService;
import org.springframework.transaction.annotation.Transactional;


/**
 * 租户套餐Service业务层处理
 *
 * @author witos
 * @date 2022-03-25
 */
@Service
public class SysTenantPackageServiceImpl implements ISysTenantPackageService
{
    @Autowired
    private SysTenantPackageMapper sysTenantPackageMapper;

    @Autowired
    private SysRoleMenuMapper sysRoleMenuMapper;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private RedisService redisService;

    /**
     * 查询租户套餐
     *
     * @param id 租户套餐主键
     * @return 租户套餐
     */
    @Override
    public SysTenantPackage selectSysTenantPackageById(Long id)
    {
        return sysTenantPackageMapper.selectById(id);
    }

    /**
     * 查询租户套餐精简列表
     */
    @Override
    public List<SysSimplePackage> getSimpleList() {
        return sysTenantPackageMapper.getSimpleList();
    }

    /**
     * 查询租户套餐列表
     *
     * @param sysTenantPackage 租户套餐
     * @return 租户套餐
     */
    @Override
    public IPage<SysTenantPackage> selectSysTenantPackageList(SysTenantPackage sysTenantPackage)
    {
        return sysTenantPackageMapper.selectSysTenantPackageList(sysTenantPackage);
    }

    /**
     * 新增租户套餐
     *
     * @param sysTenantPackage 租户套餐
     * @return 结果
     */
    @Override
    public int insertSysTenantPackage(SysTenantPackage sysTenantPackage)
    {
        return sysTenantPackageMapper.insert(sysTenantPackage);
    }

    /**
     * 修改租户套餐
     *
     * @param sysTenantPackage 租户套餐
     * @return 结果
     */
    @Override
    @Transactional
    public AjaxResult updateSysTenantPackage(SysTenantPackage sysTenantPackage)
    {
        //判断是否有 租户使用此套餐
        if(sysTenantPackage.getStatus() == 1){
           Integer activeTenants =  sysTenantPackageMapper.getActiveTenantByPackage(sysTenantPackage.getId());
           if (activeTenants > 0){
               //目前有正常的租户在使用此套餐，不允许关闭套餐
              return AjaxResult.error("租户套餐已经被使用，无法被停用！");
           }
        }
        //租户套餐修改逻辑优化,踢掉当前所有登陆此套餐的用户
        //先查询租户套餐有没有被改变
        SysTenantPackage old  = sysTenantPackageMapper.selectById(sysTenantPackage.getId());
        Map<String, String[]>  packageIsChange = StringUtils.CompareStringArray(old.getMenuIds().split(","),sysTenantPackage.getMenuIds().split(","));
        boolean excludeUser = packageIsChange.get("deleteArr") !=null && packageIsChange.get("deleteArr").length>0;
        if (excludeUser){
            //查询当前套餐跟哪些租户做了绑定
            List<SysTenant> tenants =  sysTenantPackageMapper.getTenantByPackage(sysTenantPackage.getId());
            tenants.stream().forEach(tenant->{
                //踢掉当前租户下的用户
                //只有原始套餐有菜单被删除的时候才会涉及到用户权限问题
                Collection<String> keys = redisService.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
                for (String key : keys)
                {
                    LoginUser onlineUser = redisService.getCacheObject(key);
                    if(onlineUser != null
                            && onlineUser.getSysUser() != null
                            && onlineUser.getSysUser().getTenantId() != null
                            && onlineUser.getSysUser().getTenantId().equals(tenant.getId()))
                    {
                        redisService.deleteObject(key);
                    }
                }
            });
        }
        return AjaxResult.success(sysTenantPackageMapper.updateById(sysTenantPackage));
    }

    /**
     * 批量删除租户套餐
     *
     * @param ids 需要删除的租户套餐主键
     * @return 结果
     */
    @Override
    public AjaxResult deleteSysTenantPackageByIds(Long[] ids)
    {
        //判断是否有 租户使用此套餐
        Integer activeTenants =  sysTenantPackageMapper.getActiveTenantByPackage(ids[0]);
        if (activeTenants > 0){
            //目前有正常的租户在使用此套餐，不允许关闭套餐
            return AjaxResult.error("租户套餐已经被使用，无法被删除！");
        }
        return AjaxResult.success(sysTenantPackageMapper.deleteSysTenantPackageByIds(ids));
    }

    /**
     * 查询导出
     * @return 租户套餐
     */
    @Override
    public List<SysTenantPackage> selectSysTenantPackageExport(SysTenantPackage sysTenantPackage) {
        return sysTenantPackageMapper.selectSysTenantPackageExport(sysTenantPackage);
    }
}
