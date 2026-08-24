package com.witos.ems.server.controller;

import java.util.Map;

import com.witos.common.core.web.controller.BaseController;
import com.witos.common.core.web.domain.AjaxResult;
import com.witos.common.log.annotation.Log;
import com.witos.common.log.enums.BusinessType;
import com.witos.ems.server.service.EmsDeviceService;
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
@RequestMapping("/ems/device")
public class EmsDeviceController extends BaseController
{
    @Autowired
    private EmsDeviceService deviceService;

    @GetMapping("/list")
    public AjaxResult list(@RequestParam Map<String, String> query)
    {
        return AjaxResult.success(deviceService.list(query, null));
    }

    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(deviceService.get(id));
    }

    @Log(title = "设备管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody Map<String, Object> body)
    {
        return success(deviceService.save(body, null));
    }

    @Log(title = "设备管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody Map<String, Object> body)
    {
        return success(deviceService.save(body, null));
    }

    @Log(title = "设备管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable("id") Long id)
    {
        return toAjax(deviceService.remove(id));
    }
}
