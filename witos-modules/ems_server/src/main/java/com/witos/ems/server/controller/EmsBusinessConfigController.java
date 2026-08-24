package com.witos.ems.server.controller;

import java.util.Map;

import com.witos.common.core.web.controller.BaseController;
import com.witos.common.core.web.domain.AjaxResult;
import com.witos.common.log.annotation.Log;
import com.witos.common.log.enums.BusinessType;
import com.witos.ems.server.service.EmsBusinessConfigService;
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
@RequestMapping("/ems/business-config")
public class EmsBusinessConfigController extends BaseController
{
    @Autowired
    private EmsBusinessConfigService businessConfigService;

    @GetMapping("/list")
    public AjaxResult list(@RequestParam Map<String, String> query)
    {
        return AjaxResult.success(businessConfigService.list(query));
    }

    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(businessConfigService.get(id));
    }

    @GetMapping("/templates")
    public AjaxResult templates()
    {
        return success(businessConfigService.templates());
    }

    @GetMapping("/core-values")
    public AjaxResult coreValues(@RequestParam Map<String, String> query)
    {
        return success(businessConfigService.coreValues(query));
    }

    @Log(title = "业务配置", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody Map<String, Object> body)
    {
        return success(businessConfigService.save(body));
    }

    @Log(title = "业务配置", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody Map<String, Object> body)
    {
        return success(businessConfigService.save(body));
    }

    @Log(title = "业务配置", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable("id") Long id)
    {
        return toAjax(businessConfigService.remove(id));
    }
}
