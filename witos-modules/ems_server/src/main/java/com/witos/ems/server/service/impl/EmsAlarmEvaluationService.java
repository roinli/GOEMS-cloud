package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.ems.server.domain.entity.EmsAlarmEvent;
import com.witos.ems.server.domain.entity.EmsAlarmRule;
import com.witos.ems.server.domain.entity.EmsDevice;
import com.witos.ems.server.domain.entity.EmsMetricSnapshot;
import com.witos.ems.server.mapper.EmsAlarmEventMapper;
import com.witos.ems.server.mapper.EmsAlarmRuleMapper;
import com.witos.ems.server.mapper.EmsDeviceMapper;
import com.witos.ems.server.mapper.EmsMetricSnapshotMapper;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class EmsAlarmEvaluationService
{
    @Resource
    private EmsAlarmRuleMapper alarmRuleMapper;

    @Resource
    private EmsAlarmEventMapper alarmEventMapper;

    @Resource
    private EmsMetricSnapshotMapper metricSnapshotMapper;

    @Resource
    private EmsDeviceMapper deviceMapper;

    @Transactional(rollbackFor = Exception.class)
    public int evaluateCurrentTenant()
    {
        Long tenantId = EmsRequestSupport.currentTenantId();
        List<EmsAlarmRule> rules = alarmRuleMapper.selectList(new LambdaQueryWrapper<EmsAlarmRule>()
                .eq(EmsAlarmRule::getTenantId, tenantId)
                .eq(EmsAlarmRule::getEnabled, "1")
                .eq(EmsAlarmRule::getDelFlag, "0"));
        if (rules.isEmpty())
        {
            return 0;
        }
        List<EmsMetricSnapshot> snapshots = metricSnapshotMapper.selectList(new LambdaQueryWrapper<EmsMetricSnapshot>()
                .eq(EmsMetricSnapshot::getTenantId, tenantId)
                .eq(EmsMetricSnapshot::getQuality, "GOOD")
                .isNotNull(EmsMetricSnapshot::getMetricValue));
        if (snapshots.isEmpty())
        {
            return 0;
        }

        Map<Long, EmsDevice> devices = deviceMap(tenantId);
        Set<String> evaluated = new HashSet<String>();
        int affectedRows = 0;
        for (EmsMetricSnapshot snapshot : snapshots)
        {
            EmsDevice device = devices.get(snapshot.getDeviceId());
            if (device == null || !"0".equals(device.getDelFlag()))
            {
                continue;
            }
            for (EmsAlarmRule rule : rules)
            {
                if (!matches(rule, snapshot, device))
                {
                    continue;
                }
                String alarmCode = alarmCode(rule, snapshot);
                String key = snapshot.getDeviceId() + ":" + alarmCode;
                if (!evaluated.add(key))
                {
                    continue;
                }
                boolean triggered = evaluate(rule, snapshot.getMetricValue());
                EmsAlarmEvent active = alarmEventMapper.selectActiveMergeTarget(tenantId, snapshot.getStationId(),
                        snapshot.getDeviceId(), alarmCode);
                if (triggered)
                {
                    affectedRows += upsertActiveAlarm(rule, snapshot, device, alarmCode, active);
                }
                else if (active != null)
                {
                    affectedRows += clearActiveAlarm(active);
                }
            }
        }
        return affectedRows;
    }

    private Map<Long, EmsDevice> deviceMap(Long tenantId)
    {
        List<EmsDevice> devices = deviceMapper.selectList(new LambdaQueryWrapper<EmsDevice>()
                .eq(EmsDevice::getTenantId, tenantId)
                .eq(EmsDevice::getDelFlag, "0"));
        Map<Long, EmsDevice> result = new HashMap<Long, EmsDevice>();
        for (EmsDevice device : devices)
        {
            result.put(device.getId(), device);
        }
        return result;
    }

    private boolean matches(EmsAlarmRule rule, EmsMetricSnapshot snapshot, EmsDevice device)
    {
        if (!sameMetric(rule.getMetricKey(), snapshot.getMetricKey()))
        {
            return false;
        }
        if (rule.getCompanyId() != null && rule.getCompanyId() > 0
                && !rule.getCompanyId().equals(snapshot.getCompanyId()))
        {
            return false;
        }
        if (rule.getStationId() != null && rule.getStationId() > 0
                && !rule.getStationId().equals(snapshot.getStationId()))
        {
            return false;
        }
        return rule.getDeviceType() == null || rule.getDeviceType().trim().isEmpty()
                || rule.getDeviceType().equalsIgnoreCase(device.getDeviceType());
    }

    private boolean sameMetric(String ruleMetricKey, String snapshotMetricKey)
    {
        return ruleMetricKey != null && snapshotMetricKey != null
                && ruleMetricKey.equalsIgnoreCase(snapshotMetricKey);
    }

    private boolean evaluate(EmsAlarmRule rule, BigDecimal value)
    {
        BigDecimal threshold = rule.getThresholdValue();
        if (value == null || threshold == null)
        {
            return false;
        }
        int compare = value.compareTo(threshold);
        String op = rule.getConditionOp() == null ? "GT" : rule.getConditionOp().trim().toUpperCase();
        if ("GTE".equals(op) || "GE".equals(op))
        {
            return compare >= 0;
        }
        if ("LT".equals(op))
        {
            return compare < 0;
        }
        if ("LTE".equals(op) || "LE".equals(op))
        {
            return compare <= 0;
        }
        if ("EQ".equals(op))
        {
            return compare == 0;
        }
        if ("NE".equals(op))
        {
            return compare != 0;
        }
        return compare > 0;
    }

    private int upsertActiveAlarm(EmsAlarmRule rule,
                                  EmsMetricSnapshot snapshot,
                                  EmsDevice device,
                                  String alarmCode,
                                  EmsAlarmEvent active)
    {
        Date now = new Date();
        Date eventTime = snapshot.getSampleTime() == null ? now : snapshot.getSampleTime();
        if (active != null)
        {
            active.setLastTime(eventTime);
            active.setOccurrenceCount(active.getOccurrenceCount() == null ? 2 : active.getOccurrenceCount() + 1);
            active.setAlarmName(alarmName(rule, snapshot, device));
            active.setSeverity(severity(rule));
            active.setSourcePayload(sourcePayload(rule, snapshot));
            active.setUpdateBy("system");
            active.setUpdateTime(now);
            return alarmEventMapper.updateById(active);
        }

        EmsAlarmEvent event = new EmsAlarmEvent();
        event.setTenantId(snapshot.getTenantId());
        event.setCompanyId(snapshot.getCompanyId());
        event.setStationId(snapshot.getStationId());
        event.setDeviceId(snapshot.getDeviceId());
        event.setRuleId(rule.getId());
        event.setAlarmCode(alarmCode);
        event.setAlarmName(alarmName(rule, snapshot, device));
        event.setSeverity(severity(rule));
        event.setAlarmStatus("ACTIVE");
        event.setOccurrenceCount(1);
        event.setFirstTime(eventTime);
        event.setLastTime(eventTime);
        event.setSourceType("ALARM_RULE");
        event.setSourcePayload(sourcePayload(rule, snapshot));
        event.setCreateBy("system");
        event.setCreateTime(now);
        event.setUpdateBy("system");
        event.setUpdateTime(now);
        return alarmEventMapper.insert(event);
    }

    private int clearActiveAlarm(EmsAlarmEvent active)
    {
        Date now = new Date();
        active.setAlarmStatus("CLEARED");
        active.setClearTime(now);
        active.setClearType("AUTO");
        active.setClearBy("system");
        active.setUpdateBy("system");
        active.setUpdateTime(now);
        return alarmEventMapper.updateById(active);
    }

    private String alarmCode(EmsAlarmRule rule, EmsMetricSnapshot snapshot)
    {
        return "RULE_" + rule.getId() + "_" + snapshot.getMetricKey();
    }

    private String alarmName(EmsAlarmRule rule, EmsMetricSnapshot snapshot, EmsDevice device)
    {
        String baseName = rule.getRuleName() == null || rule.getRuleName().trim().isEmpty()
                ? snapshot.getMetricKey() + "阈值告警"
                : rule.getRuleName();
        return device.getDeviceName() + " - " + baseName;
    }

    private String severity(EmsAlarmRule rule)
    {
        return rule.getSeverity() == null || rule.getSeverity().trim().isEmpty()
                ? "HIGH"
                : rule.getSeverity().trim().toUpperCase();
    }

    private String sourcePayload(EmsAlarmRule rule, EmsMetricSnapshot snapshot)
    {
        return "{"
                + "\"metricKey\":\"" + snapshot.getMetricKey() + "\","
                + "\"value\":\"" + snapshot.getMetricValue() + "\","
                + "\"conditionOp\":\"" + rule.getConditionOp() + "\","
                + "\"threshold\":\"" + rule.getThresholdValue() + "\""
                + "}";
    }
}
