package com.witos.system.controller;

import java.util.List;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.witos.common.log.annotation.Log;
import com.witos.common.log.enums.BusinessType;
import com.witos.common.core.domain.R;
import com.witos.common.security.annotation.RequiresPermissions;
import com.witos.common.security.annotation.InnerAuth;
import com.witos.system.api.domain.SysTenantRegister;
import com.witos.system.domain.SysTenant;
import com.witos.system.service.ISysTenantService;
import com.witos.common.core.web.controller.BaseController;
import com.witos.common.core.web.domain.AjaxResult;
import com.witos.common.core.utils.poi.ExcelUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;

/**
 * 租户管理Controller
 *
 * @author witos
 * @date 2022-04-11
 */
@RestController
@RequestMapping("/tenant")
public class SysTenantController extends BaseController
{
    @Autowired
    private ISysTenantService sysTenantService;

    /**
     * 查询租户管理列表
     */
    @RequiresPermissions("system:tenant:list")
    @GetMapping("/list")
    public AjaxResult list(SysTenant sysTenant)
    {
        IPage<SysTenant> list = sysTenantService.selectSysTenantPage(sysTenant);
        return AjaxResult.success(list);
    }

    /**
     * 导出租户管理列表
     */
    @RequiresPermissions("system:tenant:export")
    @Log(title = "租户管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, SysTenant sysTenant)
    {
        List<SysTenant> list = sysTenantService.selectSysTenantList(sysTenant);
        ExcelUtil<SysTenant> util = new ExcelUtil<SysTenant>(SysTenant.class);
        util.exportExcel(response, list, "租户管理数据");
    }

    /**
     * 获取租户管理详细信息
     */
    @RequiresPermissions("system:tenant:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return AjaxResult.success(sysTenantService.selectSysTenantById(id));
    }

    /**
     * 新增租户管理
     */
    @RequiresPermissions("system:tenant:add")
    @Log(title = "租户管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody SysTenant sysTenant)
    {
        return sysTenantService.insertSysTenant(sysTenant);
    }

    @InnerAuth
    @PostMapping("/register-installer")
    public R<Object> registerInstaller(@RequestBody SysTenantRegister body)
    {
        SysTenant tenant = new SysTenant();
        tenant.setId(System.currentTimeMillis());
        tenant.setTenantName(body.getCompanyName());
        tenant.setUserName(body.getUsername());
        tenant.setAdminPassword(body.getPassword());
        tenant.setUserPhone(body.getPhonenumber());
        tenant.setUserEmail(body.getEmail());
        tenant.setStatus(0);
        tenant.setDelFlag("0");
        AjaxResult result = sysTenantService.insertSysTenant(tenant);
        if (result.isError())
        {
            return R.fail(String.valueOf(result.get(AjaxResult.MSG_TAG)));
        }
        return R.ok(result.get(AjaxResult.DATA_TAG));
    }

    /**
     * 修改租户管理
     */
    @RequiresPermissions("system:tenant:edit")
    @Log(title = "租户管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody SysTenant sysTenant)
    {
        return toAjax(sysTenantService.updateSysTenant(sysTenant));
    }

    /**
     * 删除租户管理
     */
    @RequiresPermissions("system:tenant:remove")
    @Log(title = "租户管理", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(sysTenantService.deleteSysTenantByIds(ids));
    }
}
