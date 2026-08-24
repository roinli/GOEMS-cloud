package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.auth.EmsDataScope;
import com.witos.ems.server.domain.entity.EmsAlarmEvent;
import com.witos.ems.server.domain.entity.EmsAlarmMailConfig;
import com.witos.ems.server.domain.entity.EmsAlarmNotifyLog;
import com.witos.ems.server.domain.entity.EmsAlarmRule;
import com.witos.ems.server.domain.entity.EmsCompany;
import com.witos.ems.server.domain.entity.EmsStation;
import com.witos.ems.server.domain.entity.EmsDevice;
import com.witos.ems.server.mapper.EmsAlarmEventMapper;
import com.witos.ems.server.mapper.EmsAlarmMailConfigMapper;
import com.witos.ems.server.mapper.EmsAlarmNotifyLogMapper;
import com.witos.ems.server.mapper.EmsAlarmRuleMapper;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.ems.server.mapper.EmsDeviceMapper;
import com.witos.ems.server.service.EmsAlarmService;
import com.witos.ems.server.support.EmsPageSupport;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class EmsAlarmServiceImpl implements EmsAlarmService
{
    @Resource
    private EmsAlarmRuleMapper alarmRuleMapper;

    @Resource
    private EmsAlarmEventMapper alarmEventMapper;

    @Resource
    private EmsAlarmMailConfigMapper alarmMailConfigMapper;

    @Resource
    private EmsAlarmNotifyLogMapper alarmNotifyLogMapper;

    @Resource
    private EmsAuthScopeService authScopeService;

    @Resource
    private EmsBusinessParamResolver businessParamResolver;

    @Resource
    private EmsAlarmNotifyService alarmNotifyService;

    @Resource
    private EmsCompanyMapper companyMapper;

    @Resource
    private EmsStationMapper stationMapper;

    @Resource
    private EmsDeviceMapper deviceMapper;

    @Override
    public List<Map<String, Object>> current(Map<String, String> query)
    {
        return alarmEventMapper.selectCurrentAlarmList(queryMap(query), authScopeService.currentScope());
    }

    @Override
    public IPage<Map<String, Object>> history(Map<String, String> query)
    {
        return alarmEventMapper.selectAlarmEventPage(EmsPageSupport.page(), queryMap(query), authScopeService.currentScope());
    }

    @Override
    public List<Map<String, Object>> historyList(Map<String, String> query)
    {
        return alarmEventMapper.selectAlarmEventList(queryMap(query), authScopeService.currentScope());
    }

    @Override
    public IPage<Map<String, Object>> rules(Map<String, String> query)
    {
        return alarmRuleMapper.selectAlarmRulePage(EmsPageSupport.page(), queryMap(query), authScopeService.currentScope());
    }

    @Override
    public Map<String, Object> getRule(Long id)
    {
        Map<String, Object> detail = alarmRuleMapper.selectAlarmRuleDetail(id, authScopeService.currentScope());
        return detail == null ? new LinkedHashMap<String, Object>() : normalizeRuleDetail(detail);
    }

    @Override
    public Map<String, Object> saveRule(Map<String, Object> body)
    {
        Long id = EmsRequestSupport.coalesceId(body, "id");
        EmsAlarmRule current = null;
        if (id != null)
        {
            Map<String, Object> detail = alarmRuleMapper.selectAlarmRuleDetail(id, authScopeService.currentScope());
            if (detail == null || detail.isEmpty())
            {
                throw new ServiceException("告警规则不存在或无权修改");
            }
            current = alarmRuleMapper.selectById(id);
            if (current == null)
            {
                throw new ServiceException("告警规则不存在");
            }
        }
        Long companyId = EmsRequestSupport.asLong(body.get("companyId"));
        if (companyId == null)
        {
            companyId = 0L;
        }
        Long stationId = EmsRequestSupport.asLong(body.get("stationId"));
        if (stationId == null)
        {
            stationId = 0L;
        }
        String ruleName = EmsRequestSupport.stringValue(body.get("ruleName"));
        if (StringUtils.isEmpty(ruleName))
        {
            throw new ServiceException("规则名称不能为空");
        }
        Long tenantId = resolveOwnedTenant(body, companyId, stationId, current == null ? null : current.getTenantId());

        EmsAlarmRule rule = new EmsAlarmRule();
        rule.setId(id);
        rule.setTenantId(tenantId);
        rule.setCompanyId(companyId);
        rule.setStationId(stationId);
        rule.setRuleName(ruleName);
        rule.setMetricKey(EmsRequestSupport.stringValue(body.get("metricKey")));
        rule.setDeviceType(EmsRequestSupport.stringValue(body.get("deviceType")));
        rule.setConditionOp(EmsRequestSupport.defaultString(body.get("conditionOp"), "GT"));
        rule.setThresholdValue(EmsRequestSupport.asBigDecimal(body.get("thresholdValue")));
        rule.setDurationSeconds(EmsRequestSupport.asInteger(body.get("durationSeconds"), 0));
        rule.setSeverity(EmsRequestSupport.defaultString(body.get("severity"), "WARNING"));
        rule.setEnabled(EmsRequestSupport.defaultString(body.get("enabled"), "1"));
        rule.setNotifyEnabled(EmsRequestSupport.defaultString(body.get("notifyEnabled"), "1"));
        rule.setNotifyChannels("EMAIL");
        rule.setNotifyTargets(joinValues(body.get("notifyTargets"), ""));
        rule.setRemark(EmsRequestSupport.stringValue(body.get("remark")));

        if (id == null)
        {
            alarmRuleMapper.insert(rule);
            id = rule.getId();
        }
        else
        {
            alarmRuleMapper.updateById(rule);
        }
        return getRule(id);
    }

    @Override
    public IPage<Map<String, Object>> mailConfigs(Map<String, String> query)
    {
        EmsDataScope scope = authScopeService.currentScope();
        LambdaQueryWrapper<EmsAlarmMailConfig> wrapper = new LambdaQueryWrapper<EmsAlarmMailConfig>()
                .eq(!scope.isPlatformFullAccess(), EmsAlarmMailConfig::getTenantId, EmsRequestSupport.currentTenantId());
        Long companyId = EmsRequestSupport.asLong(query == null ? null : query.get("companyId"));
        String enabled = EmsRequestSupport.stringValue(query == null ? null : query.get("enabled"));
        String smtpHost = EmsRequestSupport.stringValue(query == null ? null : query.get("smtpHost"));
        if (companyId != null)
        {
            wrapper.eq(EmsAlarmMailConfig::getCompanyId, companyId);
        }
        if (StringUtils.isNotEmpty(enabled))
        {
            wrapper.eq(EmsAlarmMailConfig::getEnabled, enabled);
        }
        if (StringUtils.isNotEmpty(smtpHost))
        {
            wrapper.like(EmsAlarmMailConfig::getSmtpHost, smtpHost);
        }
        applyCompanyScope(wrapper, scope, EmsAlarmMailConfig::getCompanyId);
        wrapper.orderByDesc(EmsAlarmMailConfig::getCompanyId)
                .orderByDesc(EmsAlarmMailConfig::getUpdateTime)
                .orderByDesc(EmsAlarmMailConfig::getId);
        IPage<EmsAlarmMailConfig> page = alarmMailConfigMapper.selectPage(EmsPageSupport.page(), wrapper);
        Map<Long, String> companyNames = buildCompanyNameMap(page.getRecords().stream().map(EmsAlarmMailConfig::getCompanyId).collect(Collectors.toList()));
        Page<Map<String, Object>> result = new Page<Map<String, Object>>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", item.getId());
            row.put("companyId", item.getCompanyId());
            row.put("companyName", item.getCompanyId() == null || item.getCompanyId() == 0 ? "全部公司" : companyNames.getOrDefault(item.getCompanyId(), "-"));
            row.put("smtpHost", item.getSmtpHost());
            row.put("smtpPort", item.getSmtpPort());
            row.put("smtpUsername", item.getSmtpUsername());
            row.put("fromAddress", item.getFromAddress());
            row.put("fromName", item.getFromName());
            row.put("sslEnabled", item.getSslEnabled());
            row.put("starttlsEnabled", item.getStarttlsEnabled());
            row.put("authEnabled", item.getAuthEnabled());
            row.put("enabled", item.getEnabled());
            row.put("remark", item.getRemark());
            row.put("createTime", item.getCreateTime());
            row.put("updateTime", item.getUpdateTime());
            return row;
        }).collect(Collectors.toList()));
        return result;
    }

    @Override
    public Map<String, Object> getMailConfig(Long id)
    {
        EmsAlarmMailConfig entity = alarmMailConfigMapper.selectById(id);
        if (entity == null
                || (!EmsRequestSupport.isPlatformAdmin()
                    && !Objects.equals(entity.getTenantId(), EmsRequestSupport.currentTenantId()))
                || !inCompanyScope(entity.getCompanyId(), authScopeService.currentScope()))
        {
            return new LinkedHashMap<String, Object>();
        }
        return toMailConfigMap(entity, buildCompanyNameMap(Collections.singletonList(entity.getCompanyId())));
    }

    @Override
    public Map<String, Object> saveMailConfig(Map<String, Object> body)
    {
        Long id = EmsRequestSupport.coalesceId(body, "id");
        EmsAlarmMailConfig current = id == null ? null : alarmMailConfigMapper.selectById(id);
        if (current != null && ((!EmsRequestSupport.isPlatformAdmin()
                && !Objects.equals(current.getTenantId(), EmsRequestSupport.currentTenantId()))
                || !inCompanyScope(current.getCompanyId(), authScopeService.currentScope())))
        {
            throw new ServiceException("邮件配置不存在或无权修改");
        }
        Long companyId = EmsRequestSupport.asLong(body.get("companyId"));
        if (companyId == null)
        {
            companyId = 0L;
        }
        if (!inCompanyScope(companyId, authScopeService.currentScope()))
        {
            throw new ServiceException("公司超出当前权限范围");
        }
        Long tenantId = resolveOwnedTenant(body, companyId, 0L, current == null ? null : current.getTenantId());
        String smtpHost = EmsRequestSupport.stringValue(body.get("smtpHost"));
        if (StringUtils.isEmpty(smtpHost))
        {
            throw new ServiceException("SMTP服务器不能为空");
        }
        EmsAlarmMailConfig entity = new EmsAlarmMailConfig();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setCompanyId(companyId);
        entity.setSmtpHost(smtpHost);
        entity.setSmtpPort(EmsRequestSupport.asInteger(body.get("smtpPort"), 25));
        entity.setSmtpUsername(EmsRequestSupport.stringValue(body.get("smtpUsername")));
        String smtpPassword = EmsRequestSupport.stringValue(body.get("smtpPassword"));
        if (StringUtils.isEmpty(smtpPassword) && current != null)
        {
            smtpPassword = current.getSmtpPassword();
        }
        entity.setSmtpPassword(smtpPassword);
        entity.setFromAddress(EmsRequestSupport.stringValue(body.get("fromAddress")));
        entity.setFromName(EmsRequestSupport.stringValue(body.get("fromName")));
        entity.setSslEnabled(EmsRequestSupport.defaultString(body.get("sslEnabled"), "0"));
        entity.setStarttlsEnabled(EmsRequestSupport.defaultString(body.get("starttlsEnabled"), "0"));
        entity.setAuthEnabled(EmsRequestSupport.defaultString(body.get("authEnabled"), "1"));
        entity.setEnabled(EmsRequestSupport.defaultString(body.get("enabled"), "1"));
        entity.setRemark(EmsRequestSupport.stringValue(body.get("remark")));
        if (id == null)
        {
            alarmMailConfigMapper.insert(entity);
        }
        else
        {
            alarmMailConfigMapper.updateById(entity);
        }
        return getMailConfig(entity.getId());
    }

    @Override
    public boolean removeMailConfig(Long id)
    {
        EmsAlarmMailConfig entity = alarmMailConfigMapper.selectById(id);
        if (entity == null
                || (!EmsRequestSupport.isPlatformAdmin()
                    && !Objects.equals(entity.getTenantId(), EmsRequestSupport.currentTenantId()))
                || !inCompanyScope(entity.getCompanyId(), authScopeService.currentScope()))
        {
            return false;
        }
        return alarmMailConfigMapper.deleteById(id) > 0;
    }

    @Override
    public IPage<Map<String, Object>> notifyLogs(Map<String, String> query)
    {
        LambdaQueryWrapper<EmsAlarmNotifyLog> wrapper = buildNotifyLogWrapper(query, authScopeService.currentScope());
        IPage<EmsAlarmNotifyLog> page = alarmNotifyLogMapper.selectPage(EmsPageSupport.page(), wrapper);
        return toNotifyLogPage(page);
    }

    @Override
    public List<Map<String, Object>> notifyLogList(Map<String, String> query)
    {
        LambdaQueryWrapper<EmsAlarmNotifyLog> wrapper = buildNotifyLogWrapper(query, authScopeService.currentScope());
        return toNotifyLogRows(alarmNotifyLogMapper.selectList(wrapper));
    }

    @Override
    public boolean removeRule(Long id)
    {
        Map<String, Object> detail = alarmRuleMapper.selectAlarmRuleDetail(id, authScopeService.currentScope());
        if (detail == null || detail.isEmpty())
        {
            return false;
        }
        return alarmRuleMapper.deleteById(id) > 0;
    }

    @Override
    public boolean ack(Long id)
    {
        Map<String, Object> detail = alarmEventMapper.selectAlarmEventDetail(id, authScopeService.currentScope());
        if (detail == null || detail.isEmpty())
        {
            throw new ServiceException("告警不存在或超出当前权限范围");
        }
        return alarmEventMapper.ackAlarmEvent(id, EmsRequestSupport.asLong(detail.get("tenantId")),
                EmsRequestSupport.currentUsername()) > 0;
    }

    @Override
    public boolean clear(Long id)
    {
        Map<String, Object> detail = alarmEventMapper.selectAlarmEventDetail(id, authScopeService.currentScope());
        if (detail == null || detail.isEmpty())
        {
            throw new ServiceException("告警不存在或超出当前权限范围");
        }
        return alarmEventMapper.clearAlarmEvent(id, EmsRequestSupport.asLong(detail.get("tenantId")),
                EmsRequestSupport.currentUsername(), "MANUAL") > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> report(Map<String, Object> body)
    {
        Long companyId = EmsRequestSupport.requiredLong(body, "companyId", "公司不能为空");
        Long stationId = EmsRequestSupport.requiredLong(body, "stationId", "电站不能为空");
        Long tenantId = resolveOwnedTenant(body, companyId, stationId, null);
        String alarmCode = EmsRequestSupport.stringValue(body.get("alarmCode"));
        if (StringUtils.isEmpty(alarmCode))
        {
            throw new ServiceException("告警编码不能为空");
        }
        Long deviceId = EmsRequestSupport.asLong(body.get("deviceId"));
        validateAlarmDevice(deviceId, tenantId, companyId, stationId);
        String deviceType = EmsRequestSupport.stringValue(body.get("deviceType"));
        String metricKey = EmsRequestSupport.stringValue(body.get("metricKey"));
        Date eventTime = EmsRequestSupport.nullableTimestamp(body.get("eventTime"));
        if (eventTime == null)
        {
            eventTime = new Date();
        }

        EmsAlarmEvent current = alarmEventMapper.selectActiveMergeTarget(tenantId, stationId, deviceId, alarmCode);
        if (current != null && current.getId() != null)
        {
            current.setLastTime(eventTime);
            current.setOccurrenceCount(current.getOccurrenceCount() == null ? 2 : current.getOccurrenceCount() + 1);
            if (StringUtils.isNotEmpty(EmsRequestSupport.stringValue(body.get("alarmName"))))
            {
                current.setAlarmName(EmsRequestSupport.stringValue(body.get("alarmName")));
            }
            if (StringUtils.isNotEmpty(EmsRequestSupport.stringValue(body.get("severity"))))
            {
                current.setSeverity(defaultSeverity(body.get("severity")));
            }
            if (StringUtils.isNotEmpty(EmsRequestSupport.stringValue(body.get("sourceType"))))
            {
                current.setSourceType(EmsRequestSupport.stringValue(body.get("sourceType")));
            }
            if (body.containsKey("sourcePayload"))
            {
                current.setSourcePayload(EmsRequestSupport.stringValue(body.get("sourcePayload")));
            }
            alarmEventMapper.updateById(current);
            return buildEventResult(current);
        }

        EmsAlarmRule matchedRule = resolveMatchedRule(body, tenantId, companyId, stationId, deviceType, metricKey);
        EmsAlarmEvent event = new EmsAlarmEvent();
        event.setTenantId(tenantId);
        event.setCompanyId(companyId);
        event.setStationId(stationId);
        event.setDeviceId(deviceId);
        event.setRuleId(matchedRule == null ? EmsRequestSupport.asLong(body.get("ruleId")) : matchedRule.getId());
        event.setAlarmCode(alarmCode);
        event.setAlarmName(EmsRequestSupport.stringValue(body.get("alarmName")));
        event.setSeverity(defaultSeverity(body.get("severity")));
        event.setAlarmStatus("ACTIVE");
        event.setOccurrenceCount(1);
        event.setFirstTime(eventTime);
        event.setLastTime(eventTime);
        event.setSourceType(EmsRequestSupport.defaultString(body.get("sourceType"), "EMS"));
        event.setSourcePayload(EmsRequestSupport.stringValue(body.get("sourcePayload")));
        alarmEventMapper.insert(event);
        if (matchedRule != null)
        {
            alarmNotifyService.notifyNewAlarm(event, matchedRule);
        }
        return buildEventResult(event);
    }

    @Override
    public int cleanupHistory()
    {
        int total = 0;
        List<Long> tenantIds = alarmEventMapper.selectDistinctTenantIdsForHistoryCleanup();
        for (Long tenantId : tenantIds)
        {
            if (tenantId == null)
            {
                continue;
            }
            Integer retentionDays = 90;
            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.DAY_OF_MONTH, -1 * Math.max(retentionDays == null ? 90 : retentionDays, 1));
            total += alarmEventMapper.cleanupHistoricalEvents(tenantId, calendar.getTime());
        }
        return total;
    }

    private String defaultSeverity(Object value)
    {
        String severity = EmsRequestSupport.defaultString(value, "HIGH");
        if ("LOW".equalsIgnoreCase(severity) || "MEDIUM".equalsIgnoreCase(severity)
                || "HIGH".equalsIgnoreCase(severity) || "CRITICAL".equalsIgnoreCase(severity))
        {
            return severity.toUpperCase();
        }
        return "HIGH";
    }

    private Map<String, Object> buildEventResult(EmsAlarmEvent event)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", event.getId());
        result.put("tenantId", event.getTenantId());
        result.put("companyId", event.getCompanyId());
        result.put("stationId", event.getStationId());
        result.put("deviceId", event.getDeviceId());
        result.put("ruleId", event.getRuleId());
        result.put("alarmCode", event.getAlarmCode());
        result.put("alarmName", event.getAlarmName());
        result.put("severity", event.getSeverity());
        result.put("alarmStatus", event.getAlarmStatus());
        result.put("occurrenceCount", event.getOccurrenceCount());
        result.put("firstTime", event.getFirstTime());
        result.put("lastTime", event.getLastTime());
        result.put("clearTime", event.getClearTime());
        result.put("clearType", event.getClearType());
        result.put("clearBy", event.getClearBy());
        return result;
    }

    private EmsAlarmRule resolveMatchedRule(Map<String, Object> body,
                                             Long tenantId,
                                             Long companyId,
                                            Long stationId,
                                            String deviceType,
                                            String metricKey)
    {
        Long ruleId = EmsRequestSupport.asLong(body.get("ruleId"));
        if (ruleId != null)
        {
            EmsAlarmRule directRule = alarmRuleMapper.selectById(ruleId);
            if (directRule != null && Objects.equals(directRule.getTenantId(), tenantId)
                    && "1".equals(directRule.getEnabled()))
            {
                return directRule;
            }
        }
        List<EmsAlarmRule> rows = alarmRuleMapper.selectMatchedAlarmRules(
                tenantId,
                companyId == null ? 0L : companyId,
                stationId == null ? 0L : stationId,
                deviceType,
                metricKey,
                defaultSeverity(body.get("severity")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> normalizeRuleDetail(Map<String, Object> detail)
    {
        detail.put("notifyChannels", splitValues(detail.get("notifyChannels")));
        return detail;
    }

    private String joinValues(Object value, String defaultValue)
    {
        if (value instanceof List)
        {
            List<?> list = (List<?>) value;
            if (list.isEmpty())
            {
                return defaultValue;
            }
            return list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(StringUtils::isNotEmpty)
                    .collect(Collectors.joining(","));
        }
        String text = EmsRequestSupport.stringValue(value).trim();
        return StringUtils.isEmpty(text) ? defaultValue : text;
    }

    private List<String> splitValues(Object value)
    {
        String text = EmsRequestSupport.stringValue(value);
        if (StringUtils.isEmpty(text))
        {
            return new java.util.ArrayList<String>();
        }
        return java.util.Arrays.stream(text.split("[,;\\n\\r]+"))
                .map(String::trim)
                .filter(StringUtils::isNotEmpty)
                .collect(Collectors.toList());
    }

    private Map<String, Object> queryMap(Map<String, String> query)
    {
        return query == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(query);
    }

    private Long resolveOwnedTenant(Map<String, Object> body, Long companyId, Long stationId, Long existingTenantId)
    {
        Long resolvedTenantId = null;
        if (stationId != null && stationId > 0)
        {
            Map<String, Object> stationDetail = stationMapper.selectStationDetail(stationId, authScopeService.currentScope());
            if (stationDetail == null || stationDetail.isEmpty())
            {
                throw new ServiceException("电站不存在或超出当前权限范围");
            }
            Long stationCompanyId = EmsRequestSupport.asLong(stationDetail.get("companyId"));
            if (companyId != null && companyId > 0 && !Objects.equals(companyId, stationCompanyId))
            {
                throw new ServiceException("电站与公司不匹配");
            }
            resolvedTenantId = EmsRequestSupport.asLong(stationDetail.get("tenantId"));
        }
        if (companyId != null && companyId > 0)
        {
            Map<String, Object> companyDetail = companyMapper.selectCompanyDetail(companyId, authScopeService.currentScope());
            if (companyDetail == null || companyDetail.isEmpty())
            {
                throw new ServiceException("公司不存在或超出当前权限范围");
            }
            Long companyTenantId = EmsRequestSupport.asLong(companyDetail.get("tenantId"));
            if (resolvedTenantId != null && !Objects.equals(resolvedTenantId, companyTenantId))
            {
                throw new ServiceException("电站与公司不属于同一租户");
            }
            resolvedTenantId = companyTenantId;
        }
        if (existingTenantId != null)
        {
            if (resolvedTenantId != null && !Objects.equals(existingTenantId, resolvedTenantId))
            {
                throw new ServiceException("不能将配置迁移到其他租户");
            }
            return existingTenantId;
        }
        return resolvedTenantId == null ? EmsRequestSupport.requestedTenantId(body) : resolvedTenantId;
    }

    private void validateAlarmDevice(Long deviceId, Long tenantId, Long companyId, Long stationId)
    {
        if (deviceId == null)
        {
            return;
        }
        EmsDevice device = deviceMapper.selectById(deviceId);
        if (device == null || !"0".equals(device.getDelFlag()))
        {
            throw new ServiceException("告警设备不存在");
        }
        if (!Objects.equals(tenantId, device.getTenantId())
                || !Objects.equals(companyId, device.getCompanyId())
                || !Objects.equals(stationId, device.getStationId()))
        {
            throw new ServiceException("告警设备与公司或电站不匹配");
        }
    }

    private Map<String, Object> toMailConfigMap(EmsAlarmMailConfig item, Map<Long, String> companyNames)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", item.getId());
        row.put("companyId", item.getCompanyId());
        row.put("companyName", item.getCompanyId() == null || item.getCompanyId() == 0 ? "全部公司" : companyNames.getOrDefault(item.getCompanyId(), "-"));
        row.put("smtpHost", item.getSmtpHost());
        row.put("smtpPort", item.getSmtpPort());
        row.put("smtpUsername", item.getSmtpUsername());
        row.put("smtpPassword", item.getSmtpPassword());
        row.put("fromAddress", item.getFromAddress());
        row.put("fromName", item.getFromName());
        row.put("sslEnabled", item.getSslEnabled());
        row.put("starttlsEnabled", item.getStarttlsEnabled());
        row.put("authEnabled", item.getAuthEnabled());
        row.put("enabled", item.getEnabled());
        row.put("remark", item.getRemark());
        row.put("createTime", item.getCreateTime());
        row.put("updateTime", item.getUpdateTime());
        return row;
    }

    private LambdaQueryWrapper<EmsAlarmNotifyLog> buildNotifyLogWrapper(Map<String, String> query, EmsDataScope scope)
    {
        LambdaQueryWrapper<EmsAlarmNotifyLog> wrapper = new LambdaQueryWrapper<EmsAlarmNotifyLog>()
                .eq(scope == null || !scope.isPlatformFullAccess(), EmsAlarmNotifyLog::getTenantId,
                        EmsRequestSupport.currentTenantId());
        Long companyId = EmsRequestSupport.asLong(query == null ? null : query.get("companyId"));
        Long stationId = EmsRequestSupport.asLong(query == null ? null : query.get("stationId"));
        String channelType = EmsRequestSupport.stringValue(query == null ? null : query.get("channelType"));
        String sendStatus = EmsRequestSupport.stringValue(query == null ? null : query.get("sendStatus"));
        String receiver = EmsRequestSupport.stringValue(query == null ? null : query.get("receiver"));
        String startTime = EmsRequestSupport.stringValue(query == null ? null : query.get("startTime"));
        String endTime = EmsRequestSupport.stringValue(query == null ? null : query.get("endTime"));
        if (companyId != null)
        {
            wrapper.eq(EmsAlarmNotifyLog::getCompanyId, companyId);
        }
        if (stationId != null)
        {
            wrapper.eq(EmsAlarmNotifyLog::getStationId, stationId);
        }
        if (StringUtils.isNotEmpty(channelType))
        {
            wrapper.eq(EmsAlarmNotifyLog::getChannelType, channelType);
        }
        if (StringUtils.isNotEmpty(sendStatus))
        {
            wrapper.eq(EmsAlarmNotifyLog::getSendStatus, sendStatus);
        }
        if (StringUtils.isNotEmpty(receiver))
        {
            wrapper.like(EmsAlarmNotifyLog::getReceiver, receiver);
        }
        if (StringUtils.isNotEmpty(startTime))
        {
            wrapper.ge(EmsAlarmNotifyLog::getTriggeredAt, EmsRequestSupport.nullableTimestamp(startTime + " 00:00:00"));
        }
        if (StringUtils.isNotEmpty(endTime))
        {
            wrapper.le(EmsAlarmNotifyLog::getTriggeredAt, EmsRequestSupport.nullableTimestamp(endTime + " 23:59:59"));
        }
        applyCompanyScope(wrapper, scope, EmsAlarmNotifyLog::getCompanyId);
        if (scope != null && scope.isScopeRestricted() && scope.getStationIds() != null && !scope.getStationIds().isEmpty())
        {
            if (scope.getCompanyIds() != null && !scope.getCompanyIds().isEmpty())
            {
                wrapper.and(w -> w.in(EmsAlarmNotifyLog::getCompanyId, scope.getCompanyIds())
                        .or().in(EmsAlarmNotifyLog::getStationId, scope.getStationIds()));
            }
            else
            {
                wrapper.in(EmsAlarmNotifyLog::getStationId, scope.getStationIds());
            }
        }
        wrapper.orderByDesc(EmsAlarmNotifyLog::getTriggeredAt).orderByDesc(EmsAlarmNotifyLog::getId);
        return wrapper;
    }

    private IPage<Map<String, Object>> toNotifyLogPage(IPage<EmsAlarmNotifyLog> page)
    {
        Page<Map<String, Object>> result = new Page<Map<String, Object>>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(toNotifyLogRows(page.getRecords()));
        return result;
    }

    private List<Map<String, Object>> toNotifyLogRows(List<EmsAlarmNotifyLog> records)
    {
        Set<Long> companyIds = new HashSet<Long>();
        Set<Long> stationIds = new HashSet<Long>();
        Set<Long> deviceIds = new HashSet<Long>();
        for (EmsAlarmNotifyLog item : records)
        {
            if (item.getCompanyId() != null && item.getCompanyId() > 0)
            {
                companyIds.add(item.getCompanyId());
            }
            if (item.getStationId() != null && item.getStationId() > 0)
            {
                stationIds.add(item.getStationId());
            }
            if (item.getDeviceId() != null && item.getDeviceId() > 0)
            {
                deviceIds.add(item.getDeviceId());
            }
        }
        Map<Long, String> companyNames = buildCompanyNameMap(companyIds.stream().collect(Collectors.toList()));
        Map<Long, String> stationNames = buildStationNameMap(stationIds.stream().collect(Collectors.toList()));
        Map<Long, String> deviceNames = buildDeviceNameMap(deviceIds.stream().collect(Collectors.toList()));
        return records.stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", item.getId());
            row.put("companyId", item.getCompanyId());
            row.put("companyName", companyNames.getOrDefault(item.getCompanyId(), item.getCompanyId() == null || item.getCompanyId() == 0 ? "全部公司" : "-"));
            row.put("stationId", item.getStationId());
            row.put("stationName", stationNames.getOrDefault(item.getStationId(), "-"));
            row.put("deviceId", item.getDeviceId());
            row.put("deviceName", deviceNames.getOrDefault(item.getDeviceId(), "-"));
            row.put("eventId", item.getEventId());
            row.put("ruleId", item.getRuleId());
            row.put("ruleName", item.getRuleName());
            row.put("channelType", item.getChannelType());
            row.put("receiver", item.getReceiver());
            row.put("sendStatus", item.getSendStatus());
            row.put("subject", item.getSubject());
            row.put("content", item.getContent());
            row.put("errorMessage", item.getErrorMessage());
            row.put("triggeredAt", item.getTriggeredAt());
            row.put("sentAt", item.getSentAt());
            return row;
        }).collect(Collectors.toList());
    }

    private Map<Long, String> buildCompanyNameMap(List<Long> companyIds)
    {
        Map<Long, String> map = new HashMap<Long, String>();
        if (companyIds == null || companyIds.isEmpty())
        {
            return map;
        }
        for (EmsCompany item : companyMapper.selectBatchIds(companyIds))
        {
            map.put(item.getId(), item.getCompanyName());
        }
        return map;
    }

    private Map<Long, String> buildStationNameMap(List<Long> stationIds)
    {
        Map<Long, String> map = new HashMap<Long, String>();
        if (stationIds == null || stationIds.isEmpty())
        {
            return map;
        }
        for (EmsStation item : stationMapper.selectBatchIds(stationIds))
        {
            map.put(item.getId(), item.getStationName());
        }
        return map;
    }

    private Map<Long, String> buildDeviceNameMap(List<Long> deviceIds)
    {
        Map<Long, String> map = new HashMap<Long, String>();
        if (deviceIds == null || deviceIds.isEmpty())
        {
            return map;
        }
        for (EmsDevice item : deviceMapper.selectBatchIds(deviceIds))
        {
            map.put(item.getId(), item.getDeviceName());
        }
        return map;
    }

    private <T> void applyCompanyScope(LambdaQueryWrapper<T> wrapper, EmsDataScope scope,
                                       com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> companyField)
    {
        if (scope == null || !scope.isScopeRestricted())
        {
            return;
        }
        List<Long> companyIds = scope.getCompanyIds();
        if (companyIds == null || companyIds.isEmpty())
        {
            wrapper.eq(companyField, -1L);
            return;
        }
        wrapper.in(companyField, companyIds);
    }

    private boolean inCompanyScope(Long companyId, EmsDataScope scope)
    {
        if (scope == null || !scope.isScopeRestricted())
        {
            return true;
        }
        if (companyId == null || companyId == 0L)
        {
            return true;
        }
        return scope.getCompanyIds() != null && scope.getCompanyIds().contains(companyId);
    }
}
