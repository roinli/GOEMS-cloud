package com.witos.ems.server.controller;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.witos.common.core.web.controller.BaseController;
import com.witos.common.core.web.domain.AjaxResult;
import com.witos.common.log.annotation.Log;
import com.witos.common.log.enums.BusinessType;
import com.witos.ems.server.service.EmsAlarmService;
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
@RequestMapping("/ems/alarm")
public class EmsAlarmController extends BaseController
{
    @Autowired
    private EmsAlarmService alarmService;

    @GetMapping("/current")
    public AjaxResult current(@RequestParam Map<String, String> query)
    {
        java.util.List<java.util.Map<String, Object>> rows = alarmService.current(query);
        return AjaxResult.success(rows, rows.size());
    }

    @GetMapping("/history")
    public AjaxResult history(@RequestParam Map<String, String> query)
    {
        return AjaxResult.success(alarmService.history(query));
    }

    @GetMapping("/rules")
    public AjaxResult rules(@RequestParam Map<String, String> query)
    {
        return AjaxResult.success(alarmService.rules(query));
    }

    @PostMapping("/events/{id}/ack")
    public AjaxResult ack(@PathVariable("id") Long id)
    {
        return toAjax(alarmService.ack(id));
    }

    @PostMapping("/events/{id}/clear")
    public AjaxResult clear(@PathVariable("id") Long id)
    {
        return toAjax(alarmService.clear(id));
    }

    @PostMapping("/events/report")
    public AjaxResult report(@RequestBody Map<String, Object> body)
    {
        return success(alarmService.report(body));
    }

    @PostMapping("/history/cleanup")
    public AjaxResult cleanup()
    {
        return success(alarmService.cleanupHistory());
    }

    @GetMapping("/rules/{id}")
    public AjaxResult getRule(@PathVariable("id") Long id)
    {
        return success(alarmService.getRule(id));
    }

    @Log(title = "告警规则", businessType = BusinessType.INSERT)
    @PostMapping("/rules")
    public AjaxResult addRule(@RequestBody Map<String, Object> body)
    {
        return success(alarmService.saveRule(body));
    }

    @Log(title = "告警规则", businessType = BusinessType.UPDATE)
    @PutMapping("/rules/{id}")
    public AjaxResult editRule(@PathVariable("id") Long id, @RequestBody Map<String, Object> body)
    {
        body.put("id", id);
        return success(alarmService.saveRule(body));
    }

    @Log(title = "告警规则", businessType = BusinessType.DELETE)
    @DeleteMapping("/rules/{id}")
    public AjaxResult removeRule(@PathVariable("id") Long id)
    {
        return toAjax(alarmService.removeRule(id));
    }

    @Log(title = "告警历史", businessType = BusinessType.EXPORT)
    @PostMapping("/history/export")
    public void exportHistory(javax.servlet.http.HttpServletResponse response,
                              @RequestParam Map<String, String> query) throws IOException
    {
        List<Map<String, Object>> rows = alarmService.historyList(query);
        for (Map<String, Object> row : rows)
        {
            row.put("severity", severityText(row.get("severity")));
            row.put("alarmStatus", alarmStatusText(row.get("alarmStatus")));
            row.put("clearType", clearTypeText(row.get("clearType")));
        }
        EmsExportSupport.writeTable(response,
                EmsExportSupport.safeFileName("ems_alarm_history"),
                Arrays.asList("首次告警时间", "最后告警时间", "恢复时间", "电站", "设备", "告警名称", "次数", "级别", "状态", "清除类型", "清除人", "确认人", "来源"),
                Arrays.asList("firstTime", "lastTime", "clearTime", "stationName", "deviceName", "alarmName", "occurrenceCount", "severity", "alarmStatus", "clearType", "clearBy", "ackBy", "sourceType"),
                rows);
    }

    @GetMapping("/mail-configs")
    public AjaxResult mailConfigs(@RequestParam Map<String, String> query)
    {
        return success(alarmService.mailConfigs(query));
    }

    @GetMapping("/mail-configs/{id}")
    public AjaxResult getMailConfig(@PathVariable("id") Long id)
    {
        return success(alarmService.getMailConfig(id));
    }

    @Log(title = "告警邮件配置", businessType = BusinessType.INSERT)
    @PostMapping("/mail-configs")
    public AjaxResult addMailConfig(@RequestBody Map<String, Object> body)
    {
        return success(alarmService.saveMailConfig(body));
    }

    @Log(title = "告警邮件配置", businessType = BusinessType.UPDATE)
    @PutMapping("/mail-configs/{id}")
    public AjaxResult editMailConfig(@PathVariable("id") Long id, @RequestBody Map<String, Object> body)
    {
        body.put("id", id);
        return success(alarmService.saveMailConfig(body));
    }

    @Log(title = "告警邮件配置", businessType = BusinessType.DELETE)
    @DeleteMapping("/mail-configs/{id}")
    public AjaxResult removeMailConfig(@PathVariable("id") Long id)
    {
        return toAjax(alarmService.removeMailConfig(id));
    }

    @GetMapping("/notify-logs")
    public AjaxResult notifyLogs(@RequestParam Map<String, String> query)
    {
        return success(alarmService.notifyLogs(query));
    }

    @Log(title = "告警通知日志", businessType = BusinessType.EXPORT)
    @PostMapping("/notify-logs/export")
    public void exportNotifyLogs(javax.servlet.http.HttpServletResponse response,
                                 @RequestParam Map<String, String> query) throws IOException
    {
        List<Map<String, Object>> rows = alarmService.notifyLogList(query);
        for (Map<String, Object> row : rows)
        {
            row.put("channelType", channelTypeText(row.get("channelType")));
            row.put("sendStatus", notifyStatusText(row.get("sendStatus")));
        }
        EmsExportSupport.writeTable(response,
                EmsExportSupport.safeFileName("ems_alarm_notify_logs"),
                Arrays.asList("触发时间", "发送时间", "公司", "电站", "设备", "告警规则", "通知通道", "接收人", "发送状态", "主题", "错误信息"),
                Arrays.asList("triggeredAt", "sentAt", "companyName", "stationName", "deviceName", "ruleName", "channelType", "receiver", "sendStatus", "subject", "errorMessage"),
                rows);
    }

    private String severityText(Object value)
    {
        String code = value == null ? "" : String.valueOf(value);
        if ("LOW".equalsIgnoreCase(code))
        {
            return "低";
        }
        if ("MEDIUM".equalsIgnoreCase(code))
        {
            return "中";
        }
        if ("HIGH".equalsIgnoreCase(code))
        {
            return "高";
        }
        if ("CRITICAL".equalsIgnoreCase(code))
        {
            return "紧急";
        }
        return code;
    }

    private String alarmStatusText(Object value)
    {
        String code = value == null ? "" : String.valueOf(value);
        if ("ACTIVE".equalsIgnoreCase(code))
        {
            return "活动中";
        }
        if ("ACKED".equalsIgnoreCase(code))
        {
            return "已确认";
        }
        if ("CLEARED".equalsIgnoreCase(code))
        {
            return "已恢复";
        }
        return code;
    }

    private String clearTypeText(Object value)
    {
        String code = value == null ? "" : String.valueOf(value);
        if ("AUTO".equalsIgnoreCase(code))
        {
            return "自动";
        }
        if ("MANUAL".equalsIgnoreCase(code))
        {
            return "手动";
        }
        return code;
    }

    private String channelTypeText(Object value)
    {
        String code = value == null ? "" : String.valueOf(value);
        if ("SMS".equalsIgnoreCase(code))
        {
            return "短信";
        }
        if ("EMAIL".equalsIgnoreCase(code))
        {
            return "邮件";
        }
        if ("INAPP".equalsIgnoreCase(code))
        {
            return "站内信";
        }
        if ("WECOM".equalsIgnoreCase(code))
        {
            return "企业微信";
        }
        return code;
    }

    private String notifyStatusText(Object value)
    {
        String code = value == null ? "" : String.valueOf(value);
        if ("SUCCESS".equalsIgnoreCase(code))
        {
            return "成功";
        }
        if ("FAILED".equalsIgnoreCase(code))
        {
            return "失败";
        }
        if ("PENDING".equalsIgnoreCase(code))
        {
            return "待发送";
        }
        return code;
    }
}
