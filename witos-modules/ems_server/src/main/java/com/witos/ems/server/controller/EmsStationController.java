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
import com.witos.ems.server.service.EmsStationService;
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
@RequestMapping("/ems/station")
public class EmsStationController extends BaseController
{
    @Autowired
    private EmsStationService stationService;

    @GetMapping("/list")
    public AjaxResult list(@RequestParam Map<String, String> query)
    {
        return AjaxResult.success(stationService.list(query));
    }

    @Log(title = "电站管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody Map<String, Object> body)
    {
        return success(stationService.save(body));
    }

    @Log(title = "电站管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody Map<String, Object> body)
    {
        return success(stationService.save(body));
    }

    @Log(title = "电站管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable("id") Long id)
    {
        return toAjax(stationService.remove(id));
    }

    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(stationService.get(id));
    }

    @PutMapping("/changeStatus")
    public AjaxResult changeStatus(@RequestBody Map<String, Object> body)
    {
        if (body.get("stationId") == null && body.get("id") != null)
        {
            body.put("stationId", body.get("id"));
        }
        return success(stationService.save(body));
    }

    @Log(title = "电站管理", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, @RequestParam Map<String, String> query) throws IOException
    {
        List<Map<String, Object>> rows = stationService.listAll(query);
        EmsExportSupport.writeTable(response,
                EmsExportSupport.safeFileName("电站管理_" + DateUtils.getDate()),
                java.util.Arrays.asList("电站编码", "电站名称", "所属公司", "电站类型", "国家/地区", "联系人", "联系电话",
                    "运行模式", "时区", "装机容量(kW)", "地址", "经度", "纬度", "投运日期", "状态", "备注"),
                java.util.Arrays.asList("stationCode", "stationName", "companyName", "stationType", "country", "contactName", "contactPhone",
                    "runMode", "timezone", "capacityKw", "address", "longitude", "latitude", "commissionDate", "status", "remark"),
                rows);
    }
}
