package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.domain.entity.EmsReportSyncTask;
import com.witos.ems.server.domain.entity.EmsStation;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsReportSyncTaskMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.ems.server.service.EmsReportSyncTaskService;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.witos.ems.server.support.EmsBusinessParamTemplate;

@Service
public class EmsReportSyncTaskServiceImpl implements EmsReportSyncTaskService
{
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    @Resource
    private EmsReportSyncTaskMapper syncTaskMapper;

    @Resource
    private EmsCompanyMapper companyMapper;

    @Resource
    private EmsStationMapper stationMapper;

    @Resource
    private EmsAuthScopeService authScopeService;

    @Resource
    private EmsPriceResolver priceResolver;

    @Resource
    private EmsBusinessParamResolver businessParamResolver;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Override
    public Map<String, Object> startTask(Map<String, Object> body)
    {
        String reportType = required(body, "reportType", "报表类型不能为空").toLowerCase();
        String periodType = defaultValue(body.get("periodType"), "DAY").toUpperCase();
        Long companyId = EmsRequestSupport.asLong(body.get("companyId"));
        Long stationId = EmsRequestSupport.asLong(body.get("stationId"));
        Long tenantId = resolveTaskTenant(body, companyId, stationId);
        Date rangeStartTime = parseDate(required(body, "rangeStartTime", "开始时间不能为空"));
        Date rangeEndTime = parseDate(required(body, "rangeEndTime", "结束时间不能为空"));
        boolean rebuild = booleanValue(body == null ? null : body.get("rebuild"));
        boolean force = rebuild || booleanValue(body == null ? null : body.get("force"));
        if (rangeStartTime.after(rangeEndTime))
        {
            throw new ServiceException("开始时间不能大于结束时间");
        }

        String taskKey = buildTaskKey(tenantId, reportType, periodType, companyId, stationId,
                rangeStartTime, rangeEndTime);
        EmsReportSyncTask existing = syncTaskMapper.selectOne(new LambdaQueryWrapper<EmsReportSyncTask>()
                .eq(EmsReportSyncTask::getTenantId, tenantId)
                .eq(EmsReportSyncTask::getTaskKey, taskKey)
                .eq(EmsReportSyncTask::getDelFlag, "0")
                .last("limit 1"));
        if (existing == null)
        {
            existing = createTask(body, tenantId, reportType, periodType, companyId, stationId,
                    rangeStartTime, rangeEndTime, taskKey);
        }
        boolean retry = STATUS_FAILED.equals(existing.getTaskStatus()) || force;
        if (!claimTask(existing, force, retry))
        {
            return toMap(reload(existing.getId()));
        }
        executeTask(existing, rebuild);
        return toMap(reload(existing.getId()));
    }

    private EmsReportSyncTask createTask(Map<String, Object> body, Long tenantId, String reportType,
                                         String periodType, Long companyId,
                                         Long stationId, Date rangeStartTime, Date rangeEndTime, String taskKey)
    {
        EmsReportSyncTask task = new EmsReportSyncTask();
        task.setTenantId(tenantId);
        task.setCompanyId(companyId == null ? 0L : companyId);
        task.setStationId(stationId == null ? 0L : stationId);
        task.setReportType(reportType);
        task.setPeriodType(periodType);
        task.setRangeStartTime(rangeStartTime);
        task.setRangeEndTime(rangeEndTime);
        task.setTaskKey(taskKey);
        task.setTaskStatus(STATUS_PENDING);
        task.setRetryCount(0);
        task.setAffectedRows(0);
        task.setSourceSystem(defaultValue(body == null ? null : body.get("sourceSystem"), "MANUAL").toUpperCase());
        task.setCreateBy(EmsRequestSupport.currentUsername());
        task.setCreateTime(new Date());
        task.setUpdateBy(EmsRequestSupport.currentUsername());
        task.setUpdateTime(new Date());
        try
        {
            syncTaskMapper.insert(task);
            return task;
        }
        catch (DuplicateKeyException ex)
        {
            return findByTaskKey(tenantId, taskKey);
        }
    }

    @Override
    public Map<String, Object> retryTask(Long id)
    {
        EmsReportSyncTask task = syncTaskMapper.selectById(id);
        if (task == null || !"0".equals(task.getDelFlag()))
        {
            throw new ServiceException("同步任务不存在");
        }
        if (!STATUS_FAILED.equals(task.getTaskStatus()))
        {
            throw new ServiceException(STATUS_RUNNING.equals(task.getTaskStatus()) ? "同步任务正在运行" : "只有失败任务可以重试");
        }
        if (!claimTask(task, false, true))
        {
            throw new ServiceException("同步任务已被其他请求抢占");
        }
        executeTask(task, false);
        return toMap(reload(task.getId()));
    }

    @Override
    public Map<String, Object> listTasks(Map<String, String> query)
    {
        LambdaQueryWrapper<EmsReportSyncTask> wrapper = new LambdaQueryWrapper<EmsReportSyncTask>()
                .eq(!EmsRequestSupport.isPlatformAdmin(), EmsReportSyncTask::getTenantId,
                        EmsRequestSupport.currentTenantId())
                .eq(EmsReportSyncTask::getDelFlag, "0")
                .orderByDesc(EmsReportSyncTask::getId);
        if (query != null)
        {
            if (StringUtils.isNotEmpty(query.get("reportType")))
            {
                wrapper.eq(EmsReportSyncTask::getReportType, query.get("reportType").toLowerCase());
            }
            if (StringUtils.isNotEmpty(query.get("periodType")))
            {
                wrapper.eq(EmsReportSyncTask::getPeriodType, query.get("periodType").toUpperCase());
            }
            if (StringUtils.isNotEmpty(query.get("taskStatus")))
            {
                wrapper.eq(EmsReportSyncTask::getTaskStatus, query.get("taskStatus").toUpperCase());
            }
        }
        List<EmsReportSyncTask> tasks = syncTaskMapper.selectList(wrapper);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        java.util.ArrayList<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
        for (EmsReportSyncTask task : tasks)
        {
            rows.add(toMap(task));
        }
        result.put("rows", rows);
        result.put("total", rows.size());
        return result;
    }

    private Map<String, Object> toMap(EmsReportSyncTask task)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        Map<String, Object> company = task.getCompanyId() == null || task.getCompanyId() <= 0 ? null : companyMapper.selectCompanyDetail(task.getCompanyId(), authScopeService.currentScope());
        Map<String, Object> station = task.getStationId() == null || task.getStationId() <= 0 ? null : stationMapper.selectStationDetail(task.getStationId(), authScopeService.currentScope());
        row.put("id", task.getId());
        row.put("companyId", task.getCompanyId());
        row.put("companyName", company == null ? "" : company.get("companyName"));
        row.put("stationId", task.getStationId());
        row.put("stationName", station == null ? "" : station.get("stationName"));
        row.put("reportType", task.getReportType());
        row.put("periodType", task.getPeriodType());
        row.put("rangeStartTime", format(task.getRangeStartTime()));
        row.put("rangeEndTime", format(task.getRangeEndTime()));
        row.put("taskKey", task.getTaskKey());
        row.put("taskStatus", task.getTaskStatus());
        row.put("retryCount", task.getRetryCount());
        row.put("affectedRows", task.getAffectedRows());
        row.put("sourceSystem", task.getSourceSystem());
        row.put("errorMessage", task.getErrorMessage());
        row.put("createBy", task.getCreateBy());
        row.put("createTime", format(task.getCreateTime()));
        row.put("executeStartTime", format(task.getExecuteStartTime()));
        row.put("executeEndTime", format(task.getExecuteEndTime()));
        row.put("durationSeconds", durationSeconds(task.getExecuteStartTime(), task.getExecuteEndTime()));
        return row;
    }

    private boolean claimTask(EmsReportSyncTask task, boolean allowSuccess, boolean retry)
    {
        Date executeStartTime = new Date();
        int claimed = syncTaskMapper.claimTask(task.getId(), task.getTenantId(), allowSuccess, retry ? 1 : 0,
                EmsRequestSupport.currentUsername(), executeStartTime);
        if (claimed == 1)
        {
            task.setTaskStatus(STATUS_RUNNING);
            task.setExecuteStartTime(executeStartTime);
            task.setRetryCount((task.getRetryCount() == null ? 0 : task.getRetryCount()) + (retry ? 1 : 0));
        }
        return claimed == 1;
    }

    private void executeTask(EmsReportSyncTask task, boolean rebuild)
    {
        try
        {
            Integer affectedRows = transactionTemplate.execute(status -> executeReportRows(task, rebuild));
            finishTask(task, STATUS_SUCCESS, affectedRows == null ? 0 : affectedRows, null);
        }
        catch (Exception ex)
        {
            finishTask(task, STATUS_FAILED, 0, errorMessage(ex));
            if (ex instanceof ServiceException)
            {
                throw (ServiceException) ex;
            }
            throw new ServiceException("报表任务执行失败：" + errorMessage(ex));
        }
    }

    private int executeReportRows(EmsReportSyncTask task, boolean rebuild)
    {
        if ("HOUR".equals(task.getPeriodType()))
        {
            return executeHourTask(task, rebuild);
        }
        Long companyId = normalizeId(task.getCompanyId());
        Long stationId = normalizeId(task.getStationId());
        String startTime = format(task.getRangeStartTime());
        String endTime = format(task.getRangeEndTime());
        if ("station".equals(task.getReportType()))
        {
            if (rebuild)
            {
                syncTaskMapper.deleteStationAggregateRows(task.getPeriodType(), task.getTenantId(), companyId, stationId, startTime, endTime);
            }
            return syncTaskMapper.insertStationAggregateRows(task.getPeriodType(), task.getTenantId(), companyId, stationId, startTime, endTime);
        }
        String deviceType = reportTypeToDeviceType(task.getReportType());
        if (rebuild)
        {
            syncTaskMapper.deleteDeviceAggregateRows(task.getPeriodType(), deviceType, task.getTenantId(), companyId, stationId, startTime, endTime);
        }
        return syncTaskMapper.insertDeviceAggregateRows(task.getPeriodType(), deviceType, task.getTenantId(), companyId, stationId, startTime, endTime);
    }

    private int executeHourTask(EmsReportSyncTask task, boolean rebuild)
    {
        Long companyId = normalizeId(task.getCompanyId());
        Long stationId = normalizeId(task.getStationId());
        String startTime = format(task.getRangeStartTime());
        String endTime = format(task.getRangeEndTime());
        if ("station".equals(task.getReportType()))
        {
            if (rebuild)
            {
                syncTaskMapper.deleteStationHourRows(task.getTenantId(), companyId, stationId, startTime, endTime);
            }
            syncTaskMapper.insertStationHourRowsFromDeviceHour(task.getTenantId(), companyId, stationId, startTime, endTime);
            int derivedRows = applyStationHourDerivedValues(task.getTenantId(), companyId, stationId, startTime, endTime);
            return derivedRows;
        }
        String deviceType = reportTypeToDeviceType(task.getReportType());
        if (rebuild)
        {
            syncTaskMapper.deleteDeviceHourRows(deviceType, task.getTenantId(), companyId, stationId, startTime, endTime);
        }
        return syncTaskMapper.insertDeviceHourRowsFrom5Min(deviceType, task.getTenantId(), companyId, stationId, startTime, endTime);
    }

    private void finishTask(EmsReportSyncTask task, String status, int affectedRows, String errorMessage)
    {
        Date executeEndTime = new Date();
        int updated = syncTaskMapper.finishTask(task.getId(), task.getTenantId(), status, affectedRows, errorMessage,
                EmsRequestSupport.currentUsername(), executeEndTime);
        if (updated != 1)
        {
            throw new ServiceException("同步任务状态更新失败");
        }
    }

    private EmsReportSyncTask findByTaskKey(Long tenantId, String taskKey)
    {
        return syncTaskMapper.selectOne(new LambdaQueryWrapper<EmsReportSyncTask>()
                .eq(EmsReportSyncTask::getTenantId, tenantId)
                .eq(EmsReportSyncTask::getTaskKey, taskKey)
                .eq(EmsReportSyncTask::getDelFlag, "0")
                .last("limit 1"));
    }

    private EmsReportSyncTask reload(Long id)
    {
        return syncTaskMapper.selectById(id);
    }

    private String errorMessage(Exception ex)
    {
        String message = ex.getMessage();
        return message == null ? ex.getClass().getSimpleName() : message.substring(0, Math.min(message.length(), 512));
    }

    private int applyStationHourDerivedValues(Long tenantId, Long companyId, Long stationId, String startTime, String endTime)
    {
        List<Map<String, Object>> rows = syncTaskMapper.selectStationHourRows(tenantId, companyId, stationId, startTime, endTime);
        int updatedRows = 0;
        for (Map<String, Object> row : rows)
        {
            Long id = EmsRequestSupport.asLong(row.get("id"));
            Long rowCompanyId = EmsRequestSupport.asLong(row.get("companyId"));
            Long rowStationId = EmsRequestSupport.asLong(row.get("stationId"));
            BigDecimal generationKwh = asDecimal(row.get("generationKwh"));
            BigDecimal chargeKwh = asDecimal(row.get("chargeKwh"));
            BigDecimal dischargeKwh = asDecimal(row.get("dischargeKwh"));
            BigDecimal gridImportKwh = asDecimal(row.get("gridImportKwh"));
            BigDecimal gridExportKwh = asDecimal(row.get("gridExportKwh"));
            Date statTime = row.get("statTime") instanceof Date ? (Date) row.get("statTime") : null;
            EmsPriceResolver.RevenueBreakdown revenue = priceResolver.resolveRevenueBreakdown(tenantId, rowCompanyId, rowStationId,
                    generationKwh, gridExportKwh, chargeKwh, dischargeKwh, gridImportKwh, statTime, true);
            BigDecimal socialGenerationKwh = generationKwh.max(BigDecimal.ZERO);
            BigDecimal co2 = socialGenerationKwh.multiply(businessParamResolver.resolveDecimal(EmsBusinessParamTemplate.CO2_FACTOR, tenantId, rowCompanyId, rowStationId));
            BigDecimal standardCoal = socialGenerationKwh.multiply(businessParamResolver.resolveDecimal(EmsBusinessParamTemplate.STANDARD_COAL_FACTOR, tenantId, rowCompanyId, rowStationId));
            BigDecimal treeFactor = businessParamResolver.resolveDecimal(EmsBusinessParamTemplate.TREE_FACTOR, tenantId, rowCompanyId, rowStationId);
            BigDecimal trees = treeFactor.compareTo(BigDecimal.ZERO) > 0 ? co2.divide(treeFactor, 8, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal equivalentHours = resolveEquivalentHours(rowStationId, socialGenerationKwh);
            int affectedRows = syncTaskMapper.updateStationHourDerivedValues(id, revenue.getRevenueAmount(), revenue.getFeedInRevenue(),
                    revenue.getSelfUseSaving(), revenue.getStorageArbitrageRevenue(), revenue.getPurchaseCost(), revenue.getQualityReason(),
                    equivalentHours, co2, standardCoal, trees);
            if (affectedRows != 1)
            {
                throw new ServiceException("电站小时报表派生值更新失败，报表行ID=" + id);
            }
            updatedRows++;
        }
        return updatedRows;
    }

    private BigDecimal resolveEquivalentHours(Long stationId, BigDecimal generationKwh)
    {
        if (stationId == null || stationId <= 0 || generationKwh == null || generationKwh.compareTo(BigDecimal.ZERO) <= 0)
        {
            return BigDecimal.ZERO;
        }
        EmsStation station = stationMapper.selectById(stationId);
        BigDecimal capacityKw = station == null ? null : station.getCapacityKw();
        if (capacityKw == null || capacityKw.compareTo(BigDecimal.ZERO) <= 0)
        {
            return BigDecimal.ZERO;
        }
        return generationKwh.divide(capacityKw, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal asDecimal(Object value)
    {
        if (value == null)
        {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal)
        {
            return (BigDecimal) value;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private String buildTaskKey(Long tenantId, String reportType, String periodType, Long companyId,
                                Long stationId, Date startTime, Date endTime)
    {
        return tenantId + ":" + reportType + ":" + periodType + ":" + (companyId == null ? 0L : companyId)
                + ":" + (stationId == null ? 0L : stationId) + ":" + format(startTime) + ":" + format(endTime);
    }

    private Long resolveTaskTenant(Map<String, Object> body, Long companyId, Long stationId)
    {
        Long tenantId = null;
        if (stationId != null && stationId > 0)
        {
            Map<String, Object> station = stationMapper.selectStationDetail(stationId, authScopeService.currentScope());
            if (station == null || station.isEmpty())
            {
                throw new ServiceException("电站不存在或超出当前授权范围");
            }
            Long stationCompanyId = EmsRequestSupport.asLong(station.get("companyId"));
            if (companyId != null && companyId > 0 && !companyId.equals(stationCompanyId))
            {
                throw new ServiceException("电站与公司不匹配");
            }
            tenantId = EmsRequestSupport.asLong(station.get("tenantId"));
        }
        if (companyId != null && companyId > 0)
        {
            Map<String, Object> company = companyMapper.selectCompanyDetail(companyId, authScopeService.currentScope());
            if (company == null || company.isEmpty())
            {
                throw new ServiceException("公司不存在或超出当前授权范围");
            }
            Long companyTenantId = EmsRequestSupport.asLong(company.get("tenantId"));
            if (tenantId != null && !tenantId.equals(companyTenantId))
            {
                throw new ServiceException("电站与公司不属于同一租户");
            }
            tenantId = companyTenantId;
        }
        return tenantId == null ? EmsRequestSupport.requestedTenantId(body) : tenantId;
    }

    private Date parseDate(String value)
    {
        try
        {
            if (value.length() == 10)
            {
                return new SimpleDateFormat("yyyy-MM-dd").parse(value);
            }
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(value);
        }
        catch (ParseException e)
        {
            throw new ServiceException("时间格式错误");
        }
    }

    private String format(Date date)
    {
        return date == null ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(date);
    }

    private Long normalizeId(Long value)
    {
        return value == null ? 0L : value;
    }

    private String reportTypeToDeviceType(String reportType)
    {
        if ("inverter".equals(reportType))
        {
            return "INVERTER";
        }
        if ("pcs".equals(reportType))
        {
            return "PCS";
        }
        if ("storage".equals(reportType))
        {
            return "ESS";
        }
        if ("meter".equals(reportType))
        {
            return "METER";
        }
        if ("controller".equals(reportType))
        {
            return "CONTROLLER";
        }
        throw new ServiceException("不支持的报表类型");
    }

    private String required(Map<String, Object> body, String key, String message)
    {
        String value = body == null ? null : String.valueOf(body.get(key));
        if (value == null || "null".equalsIgnoreCase(value) || value.trim().isEmpty())
        {
            throw new ServiceException(message);
        }
        return value;
    }

    private String defaultValue(Object value, String fallback)
    {
        if (value == null || "null".equalsIgnoreCase(String.valueOf(value)) || String.valueOf(value).trim().isEmpty())
        {
            return fallback;
        }
        return String.valueOf(value);
    }

    private boolean booleanValue(Object value)
    {
        return value != null && (Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value)));
    }

    private long durationSeconds(Date startTime, Date endTime)
    {
        if (startTime == null || endTime == null)
        {
            return 0L;
        }
        return Math.max(0L, (endTime.getTime() - startTime.getTime()) / 1000L);
    }
}
