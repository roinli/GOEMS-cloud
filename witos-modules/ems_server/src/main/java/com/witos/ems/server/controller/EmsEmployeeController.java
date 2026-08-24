package com.witos.ems.server.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import com.witos.common.core.utils.DateUtils;
import com.witos.common.core.web.controller.BaseController;
import com.witos.common.core.web.domain.AjaxResult;
import com.witos.common.log.annotation.Log;
import com.witos.common.log.enums.BusinessType;
import com.witos.ems.server.service.EmsEmployeeService;
import com.witos.ems.server.support.EmsExportSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ems/employee")
public class EmsEmployeeController extends BaseController
{
    @Autowired
    private EmsEmployeeService employeeService;

    @GetMapping("/list")
    public AjaxResult list(@RequestParam Map<String, String> query)
    {
        return AjaxResult.success(employeeService.list(query));
    }

    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(employeeService.get(id));
    }

    @GetMapping("/roleOptions")
    public AjaxResult roleOptions()
    {
        return success(employeeService.roleOptions());
    }

    @Log(title = "员工管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody Map<String, Object> body)
    {
        return success(employeeService.save(body));
    }

    @Log(title = "员工管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody Map<String, Object> body)
    {
        return success(employeeService.save(body));
    }

    @Log(title = "员工管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable("id") Long id)
    {
        return toAjax(employeeService.remove(id));
    }

    @PutMapping("/changeStatus")
    public AjaxResult changeStatus(@RequestBody Map<String, Object> body)
    {
        return success(employeeService.save(body));
    }

    @GetMapping("/deptTree")
    public AjaxResult deptTree(@RequestParam(value = "companyId", required = false) Long companyId)
    {
        return success(employeeService.deptTree(companyId));
    }

    @Log(title = "员工管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, @RequestParam Map<String, String> query) throws IOException
    {
        List<Map<String, Object>> rows = employeeService.listAll(query);
        EmsExportSupport.writeTable(response,
                EmsExportSupport.safeFileName("员工管理_" + DateUtils.getDate()),
                java.util.Arrays.asList("员工名称", "员工昵称", "公司", "角色", "部门", "手机号码", "邮箱", "状态"),
                java.util.Arrays.asList("userName", "nickName", "companyName", "roleNames", "deptName", "phonenumber", "email", "status"),
                rows);
    }
}
