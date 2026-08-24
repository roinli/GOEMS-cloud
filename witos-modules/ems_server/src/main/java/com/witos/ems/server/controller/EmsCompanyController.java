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
import com.witos.ems.server.service.EmsCompanyService;
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
@RequestMapping("/ems/company")
public class EmsCompanyController extends BaseController
{
    @Autowired
    private EmsCompanyService companyService;

    @GetMapping("/list")
    public AjaxResult list(@RequestParam Map<String, String> query)
    {
        return AjaxResult.success(companyService.list(query));
    }

    @GetMapping("/treeSelect")
    public AjaxResult tree()
    {
        return AjaxResult.success(companyService.tree());
    }

    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(companyService.get(id));
    }

    @Log(title = "公司管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody Map<String, Object> body)
    {
        return success(companyService.save(body));
    }

    @Log(title = "公司管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody Map<String, Object> body)
    {
        return success(companyService.save(body));
    }

    @Log(title = "公司管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable("id") Long id)
    {
        return toAjax(companyService.remove(id));
    }

    @Log(title = "公司管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, @RequestParam Map<String, String> query) throws IOException
    {
        List<Map<String, Object>> rows = companyService.listAll(query);
        EmsExportSupport.writeTable(response,
                EmsExportSupport.safeFileName("公司管理_" + DateUtils.getDate()),
                java.util.Arrays.asList("公司名称", "父公司", "公司描述", "国家/地区", "省", "市", "地址", "官网", "备案号", "状态"),
                java.util.Arrays.asList("companyName", "parentName", "companyDesc", "country", "province", "city", "address", "website", "recordNo", "status"),
                rows);
    }
}
