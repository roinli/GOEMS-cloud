package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.domain.entity.EmsOpenemsBackfillTask;
import com.witos.ems.server.domain.entity.EmsOpenemsDevice;
import com.witos.ems.server.domain.entity.EmsOpenemsResourceReport;
import com.witos.ems.server.mapper.EmsOpenemsBackfillTaskMapper;
import com.witos.ems.server.mapper.EmsOpenemsDeviceMapper;
import com.witos.ems.server.mapper.EmsOpenemsResourceReportMapper;
import com.witos.ems.server.service.EmsOpenemsBackfillService;
import com.witos.ems.server.service.EmsOpenemsTimeseriesService;
import com.witos.ems.server.support.EmsPageSupport;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import com.alibaba.fastjson2.JSON;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EmsOpenemsBackfillServiceImpl implements EmsOpenemsBackfillService
{
    private static final long MAX_RANGE_MILLIS = 366L * 24L * 60L * 60L * 1000L;

    @Resource
    private EmsOpenemsBackfillTaskMapper taskMapper;

    @Resource
    private EmsOpenemsDeviceMapper deviceMapper;

    @Resource
    private EmsOpenemsTimeseriesService timeseriesService;

    @Resource
    private EmsOpenemsResourceReportMapper resourceReportMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(Long deviceId, Map<String, Object> body)
    {
        EmsOpenemsDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsDevice>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsDevice::getTenantId,
                        EmsRequestSupport.currentTenantId())
                .eq(EmsOpenemsDevice::getId, deviceId)
                .eq(EmsOpenemsDevice::getDelFlag, "0")
                .last("limit 1"));
        if (device == null)
        {
            throw new ServiceException("OpenEMS设备不存在或不属于当前租户");
        }
        Long tenantId = device.getTenantId();
        if (!"ACTIVE".equals(device.getStatus()))
        {
            throw new ServiceException("只有启用状态的设备可以补拉历史数据");
        }
        if (StringUtils.isEmpty(device.getPrimaryComponentId()))
        {
            throw new ServiceException("设备未配置主Component，无法补拉");
        }
        Date from = parseRequired(body, "from", "补拉开始时间不能为空");
        Date to = parseRequired(body, "to", "补拉结束时间不能为空");
        if (!to.after(from))
        {
            throw new ServiceException("补拉结束时间必须晚于开始时间");
        }
        if (to.getTime() - from.getTime() > MAX_RANGE_MILLIS)
        {
            throw new ServiceException("单次补拉不能超过366天");
        }
        EmsOpenemsBackfillTask exact = taskMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsBackfillTask>()
                .eq(EmsOpenemsBackfillTask::getTenantId, tenantId)
                .eq(EmsOpenemsBackfillTask::getDeviceId, deviceId)
                .eq(EmsOpenemsBackfillTask::getFromTime, from)
                .eq(EmsOpenemsBackfillTask::getToTime, to)
                .eq(EmsOpenemsBackfillTask::getDelFlag, "0")
                .last("limit 1"));
        if (exact != null && ("PENDING".equals(exact.getState()) || "RUNNING".equals(exact.getState())
                || "SUCCESS".equals(exact.getState())))
        {
            return toMap(exact);
        }
        List<EmsOpenemsBackfillTask> overlaps = taskMapper.selectList(new LambdaQueryWrapper<EmsOpenemsBackfillTask>()
                .eq(EmsOpenemsBackfillTask::getTenantId, tenantId)
                .eq(EmsOpenemsBackfillTask::getDeviceId, deviceId)
                .eq(EmsOpenemsBackfillTask::getDelFlag, "0")
                .in(EmsOpenemsBackfillTask::getState, "PENDING", "RUNNING", "SUCCESS"));
        for (EmsOpenemsBackfillTask existing : overlaps)
        {
            if (overlap(from, to, existing.getFromTime(), existing.getToTime()))
            {
                throw new ServiceException("已有补拉任务进行中或已覆盖该时间范围");
            }
        }
        EmsOpenemsBackfillTask task = exact == null ? new EmsOpenemsBackfillTask() : exact;
        task.setTenantId(tenantId);
        task.setDeviceId(device.getId());
        task.setEndpointId(device.getEndpointId());
        task.setEdgeId(device.getEdgeId());
        task.setComponentId(device.getPrimaryComponentId());
        task.setFromTime(from);
        task.setToTime(to);
        task.setState("RUNNING");
        task.setSource("RAW");
        task.setProgress(BigDecimal.ZERO);
        task.setReportRebuildState("RUNNING");
        task.setStartedAt(new Date());
        task.setFinishedAt(null);
        task.setLastError(null);
        task.setCreateTime(new Date());
        task.setUpdateTime(new Date());
        task.setDelFlag("0");
        if (task.getId() == null)
        {
            try
            {
                taskMapper.insert(task);
            }
            catch (DuplicateKeyException ex)
            {
                throw new ServiceException("该端点在此时间范围已有补拉任务，请刷新任务状态后重试");
            }
        }
        try
        {
            Map<String, Object> historyQuery = new LinkedHashMap<String, Object>();
            historyQuery.put("from", String.valueOf(body.get("from")));
            historyQuery.put("to", String.valueOf(body.get("to")));
            if (body.get("channels") != null)
            {
                historyQuery.put("channels", String.valueOf(body.get("channels")));
            }
            if (body.get("intervalSeconds") != null)
            {
                historyQuery.put("intervalSeconds", String.valueOf(body.get("intervalSeconds")));
            }
            if (body.get("aggregation") != null)
            {
                historyQuery.put("aggregation", String.valueOf(body.get("aggregation")));
            }
            Map<String, String> query = new LinkedHashMap<String, String>();
            for (Map.Entry<String, Object> entry : historyQuery.entrySet())
            {
                query.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            Map<String, Object> result = timeseriesService.history(deviceId, query);
            task.setSource(String.valueOf(result.get("source")));
            materializeResourceReports(device, result);
            task.setProgress(BigDecimal.valueOf(100));
            task.setState("SUCCESS");
            task.setReportRebuildState("SUCCESS");
            if ("MISSING".equals(result.get("quality")))
            {
                task.setLastError("Influx保留期外或指定范围没有数据，报表标记为MISSING，未补零");
            }
        }
        catch (RuntimeException ex)
        {
            task.setState("FAILED");
            task.setReportRebuildState("FAILED");
            task.setLastError(errorMessage(ex));
        }
        task.setFinishedAt(new Date());
        task.setUpdateTime(new Date());
        taskMapper.updateById(task);
        return toMap(task);
    }

    @SuppressWarnings("unchecked")
    private void materializeResourceReports(EmsOpenemsDevice device, Map<String, Object> result)
    {
        Object rowsValue = result.get("rows");
        if (!(rowsValue instanceof List))
        {
            return;
        }
        Date now = new Date();
        for (Object value : (List<Object>) rowsValue)
        {
            if (!(value instanceof Map))
            {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) value;
            Object statTime = row.get("sampleTime");
            if (statTime == null)
            {
                continue;
            }
            EmsOpenemsResourceReport report = resourceReportMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsResourceReport>()
                    .eq(EmsOpenemsResourceReport::getTenantId, device.getTenantId())
                    .eq(EmsOpenemsResourceReport::getEndpointId, device.getEndpointId())
                    .eq(EmsOpenemsResourceReport::getEdgeId, device.getEdgeId())
                    .eq(EmsOpenemsResourceReport::getComponentId, device.getPrimaryComponentId())
                    .eq(EmsOpenemsResourceReport::getStatTime, statTime)
                    .eq(EmsOpenemsResourceReport::getDelFlag, "0")
                    .last("limit 1"));
            if (report == null)
            {
                report = new EmsOpenemsResourceReport();
                report.setTenantId(device.getTenantId());
                report.setDeviceId(device.getId());
                report.setEndpointId(device.getEndpointId());
                report.setEdgeId(device.getEdgeId());
                report.setComponentId(device.getPrimaryComponentId());
                report.setStatTime(parseRowTime(statTime));
                report.setDelFlag("0");
                report.setCreateTime(now);
                report.setCreateBy(EmsRequestSupport.currentUsername());
            }
            report.setValuesJson(JSON.toJSONString(row.get("values")));
            report.setSource(String.valueOf(result.get("source")));
            report.setDataQuality(String.valueOf(result.get("quality")));
            report.setQualityReason(String.valueOf(result.get("fallbackReason")));
            report.setCompanyId(device.getCompanyId());
            report.setStationId(device.getStationId());
            if (device.getCompanyId() == null || device.getStationId() == null)
            {
                report.setRevenueStatus("NOT_APPLICABLE");
                report.setRevenueAmount(null);
                report.setRevenueQualityReason("未绑定公司和电站");
            }
            else
            {
                report.setRevenueStatus("ELIGIBLE");
                report.setRevenueAmount(null);
                report.setRevenueQualityReason("资源级补拉已物化；收益按绑定区间和电价规则由后续业务重算");
            }
            report.setUpdateTime(now);
            report.setUpdateBy(EmsRequestSupport.currentUsername());
            if (report.getId() == null)
            {
                resourceReportMapper.insert(report);
            }
            else
            {
                resourceReportMapper.updateById(report);
            }
        }
    }

    private Date parseRowTime(Object value)
    {
        if (value instanceof Date)
        {
            return (Date) value;
        }
        try
        {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(String.valueOf(value).replace('T', ' '));
        }
        catch (Exception ex)
        {
            throw new ServiceException("Influx返回的采样时间无效");
        }
    }

    @Override
    public Map<String, Object> get(Long id)
    {
        EmsOpenemsBackfillTask task = taskMapper.selectOne(new LambdaQueryWrapper<EmsOpenemsBackfillTask>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsBackfillTask::getTenantId,
                        EmsRequestSupport.currentTenantId())
                .eq(EmsOpenemsBackfillTask::getId, id)
                .eq(EmsOpenemsBackfillTask::getDelFlag, "0")
                .last("limit 1"));
        if (task == null)
        {
            throw new ServiceException("补拉任务不存在或不属于当前租户");
        }
        return toMap(task);
    }

    @Override
    public Map<String, Object> list(Map<String, String> query)
    {
        LambdaQueryWrapper<EmsOpenemsBackfillTask> wrapper = new LambdaQueryWrapper<EmsOpenemsBackfillTask>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsOpenemsBackfillTask::getTenantId,
                        EmsRequestSupport.currentTenantId())
                .eq(EmsOpenemsBackfillTask::getDelFlag, "0")
                .orderByDesc(EmsOpenemsBackfillTask::getId);
        if (query != null && StringUtils.isNotEmpty(query.get("deviceId")))
        {
            wrapper.eq(EmsOpenemsBackfillTask::getDeviceId, Long.valueOf(query.get("deviceId")));
        }
        if (query != null && StringUtils.isNotEmpty(query.get("state")))
        {
            wrapper.eq(EmsOpenemsBackfillTask::getState, query.get("state"));
        }
        IPage<EmsOpenemsBackfillTask> page = taskMapper.selectPage(EmsPageSupport.<EmsOpenemsBackfillTask>page(), wrapper);
        Page<Map<String, Object>> result = new Page<Map<String, Object>>(page.getCurrent(), page.getSize(), page.getTotal());
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (EmsOpenemsBackfillTask task : page.getRecords())
        {
            rows.add(toMap(task));
        }
        result.setRecords(rows);
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("records", result.getRecords());
        output.put("total", result.getTotal());
        output.put("pageNum", result.getCurrent());
        output.put("pageSize", result.getSize());
        return output;
    }

    private boolean overlap(Date from, Date to, Date otherFrom, Date otherTo)
    {
        return from.before(otherTo) && otherFrom.before(to);
    }

    private Date parseRequired(Map<String, Object> body, String key, String message)
    {
        Object value = body == null ? null : body.get(key);
        if (value == null || String.valueOf(value).trim().isEmpty())
        {
            throw new ServiceException(message);
        }
        String text = String.valueOf(value).trim();
        try
        {
            if (text.matches("\\d{4}-\\d{2}-\\d{2}"))
            {
                return new SimpleDateFormat("yyyy-MM-dd").parse(text);
            }
            if (text.endsWith("Z") || text.matches(".*[+-]\\d{2}:\\d{2}$"))
            {
                return text.endsWith("Z")
                        ? Date.from(java.time.Instant.parse(text))
                        : Date.from(java.time.OffsetDateTime.parse(text).toInstant());
            }
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(text.replace('T', ' '));
        }
        catch (Exception ex)
        {
            throw new ServiceException("补拉时间格式无效");
        }
    }

    private String errorMessage(Exception ex)
    {
        String message = ex.getMessage();
        if (message == null || message.trim().isEmpty())
        {
            return ex.getClass().getSimpleName();
        }
        // Keep persisted diagnostics bounded even after last_error is migrated
        // to TEXT; the complete exception remains in the server log.
        return message.substring(0, Math.min(message.length(), 4000));
    }

    private Map<String, Object> toMap(EmsOpenemsBackfillTask task)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", task.getId());
        row.put("deviceId", task.getDeviceId());
        row.put("endpointId", task.getEndpointId());
        row.put("edgeId", task.getEdgeId());
        row.put("componentId", task.getComponentId());
        row.put("fromTime", task.getFromTime());
        row.put("toTime", task.getToTime());
        row.put("state", task.getState());
        row.put("source", task.getSource());
        row.put("progress", task.getProgress());
        row.put("lastError", task.getLastError());
        row.put("reportRebuildState", task.getReportRebuildState());
        row.put("startedAt", task.getStartedAt());
        row.put("finishedAt", task.getFinishedAt());
        return row;
    }
}
