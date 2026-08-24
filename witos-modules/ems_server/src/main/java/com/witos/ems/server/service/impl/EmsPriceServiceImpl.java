package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.auth.EmsDataScope;
import com.witos.ems.server.domain.entity.EmsPriceApply;
import com.witos.ems.server.domain.entity.EmsPricePeriod;
import com.witos.ems.server.domain.entity.EmsPriceRule;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsPriceApplyMapper;
import com.witos.ems.server.mapper.EmsPricePeriodMapper;
import com.witos.ems.server.mapper.EmsPriceRuleMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.ems.server.service.EmsPriceService;
import com.witos.ems.server.support.EmsPageSupport;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Comparator;

@Service
public class EmsPriceServiceImpl implements EmsPriceService
{
    private static final Set<String> ALLOWED_PRICE_TYPES = new HashSet<String>(Arrays.asList("PURCHASE", "FEED_IN"));
    private static final Set<String> ALLOWED_PRICE_MODES = new HashSet<String>(Arrays.asList("SIMPLE", "TOU", "COMPOSITE", "MARKET"));
    private static final Set<String> ALLOWED_PERIOD_TYPES = new HashSet<String>(Arrays.asList("SHARP", "PEAK", "FLAT", "VALLEY", "NORMAL"));

    @Resource
    private EmsPriceRuleMapper priceRuleMapper;

    @Resource
    private EmsPricePeriodMapper pricePeriodMapper;

    @Resource
    private EmsPriceApplyMapper priceApplyMapper;

    @Resource
    private EmsCompanyMapper companyMapper;

    @Resource
    private EmsStationMapper stationMapper;

    @Resource
    private EmsAuthScopeService authScopeService;

    @Override
    public IPage<Map<String, Object>> list(Map<String, String> query)
    {
        return priceRuleMapper.selectPricePage(EmsPageSupport.page(), queryMap(query), authScopeService.currentScope());
    }

    @Override
    public List<Map<String, Object>> listAll(Map<String, String> query)
    {
        return priceRuleMapper.selectPriceList(queryMap(query), authScopeService.currentScope());
    }

    @Override
    public Map<String, Object> get(Long id)
    {
        Map<String, Object> detail = priceRuleMapper.selectPriceDetail(id, authScopeService.currentScope());
        if (detail == null || detail.isEmpty())
        {
            return new LinkedHashMap<String, Object>();
        }

        List<EmsPricePeriod> periods = pricePeriodMapper.selectList(new LambdaQueryWrapper<EmsPricePeriod>()
                .eq(EmsPricePeriod::getRuleId, id)
                .orderByAsc(EmsPricePeriod::getSortNo, EmsPricePeriod::getId));
        List<Map<String, Object>> periodRows = new ArrayList<Map<String, Object>>();
        for (EmsPricePeriod period : periods)
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", period.getId());
            row.put("periodName", period.getPeriodName());
            row.put("periodType", period.getPeriodType());
            row.put("startTime", period.getStartTime());
            row.put("endTime", period.getEndTime());
            row.put("priceValue", period.getPriceValue());
            row.put("price", period.getPriceValue());
            row.put("weekdayMask", period.getWeekdayMask());
            row.put("sortNo", period.getSortNo());
            periodRows.add(row);
        }
        detail.put("periods", periodRows);
        detail.put("isDefault", detail.get("isDefault") == null ? "1" : detail.get("isDefault"));
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Map<String, Object> body)
    {
        Long id = EmsRequestSupport.coalesceId(body, "id");
        EmsPriceRule current = id == null ? null : requireRule(id);
        Long companyId = EmsRequestSupport.asLong(body.get("companyId"));
        if (companyId == null)
        {
            companyId = 0L;
        }
        boolean tenantLevel = companyId == 0L;
        if (tenantLevel)
        {
            EmsDataScope scope = authScopeService.currentScope();
            if (!scope.isPlatformFullAccess() && !scope.isTenantFullAccess())
            {
                throw new ServiceException("仅安装商管理员可创建安装商级电价模板");
            }
        }
        Map<String, Object> companyDetail = null;
        if (!tenantLevel)
        {
            companyDetail = validateCompany(companyId);
        }
        Long tenantId = current != null ? current.getTenantId()
                : tenantLevel ? EmsRequestSupport.requestedTenantId(body)
                : EmsRequestSupport.asLong(companyDetail.get("tenantId"));
        if (current != null && companyDetail != null
                && !Objects.equals(current.getTenantId(), EmsRequestSupport.asLong(companyDetail.get("tenantId"))))
        {
            throw new ServiceException("不能将电价规则迁移到其他租户");
        }
        String ruleName = EmsRequestSupport.stringValue(body.get("ruleName"));
        if (StringUtils.isEmpty(ruleName))
        {
            throw new ServiceException("规则名称不能为空");
        }
        String priceType = EmsRequestSupport.defaultString(body.get("priceType"), "PURCHASE");
        if (!ALLOWED_PRICE_TYPES.contains(priceType))
        {
            throw new ServiceException("电价类型不合法");
        }
        String priceMode = EmsRequestSupport.defaultString(body.get("priceMode"), "SIMPLE");
        if (!ALLOWED_PRICE_MODES.contains(priceMode))
        {
            throw new ServiceException("电价模式不合法");
        }
        validatePeriods(body.get("periods"));
        String status = EmsRequestSupport.defaultString(body.get("status"), "ENABLED").toUpperCase();
        String isDefault = EmsRequestSupport.defaultString(body.get("isDefault"), "1");
        if (!"0".equals(isDefault) && !"1".equals(isDefault))
        {
            throw new ServiceException("默认标记不合法");
        }

        EmsPriceRule rule = new EmsPriceRule();
        rule.setId(id);
        rule.setTenantId(tenantId);
        rule.setCompanyId(companyId);
        rule.setRuleName(ruleName);
        rule.setPriceType(priceType);
        rule.setPriceMode(priceMode);
        rule.setCurrency("CNY");
        rule.setBasePrice(defaultPrice(body.get("basePrice")));
        rule.setIsDefault(isDefault);
        rule.setStatus(status);
        rule.setRemark(EmsRequestSupport.stringValue(body.get("remark")));

        if (id == null)
        {
            priceRuleMapper.insert(rule);
            id = rule.getId();
        }
        else
        {
            priceRuleMapper.updateById(rule);
        }

        replacePeriods(tenantId, id, body.get("periods"));
        if ("0".equals(isDefault))
        {
            clearOtherDefaultRules(tenantId, id, companyId, priceType);
        }
        return get(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> changeStatus(Long id, String status)
    {
        if (id == null)
        {
            throw new ServiceException("规则不能为空");
        }
        EmsPriceRule rule = requireRule(id);
        String targetStatus = EmsRequestSupport.defaultString(status, "ENABLED").toUpperCase();
        if (!"ENABLED".equals(targetStatus) && !"DISABLED".equals(targetStatus) && !"EXPIRED".equals(targetStatus))
        {
            throw new ServiceException("状态不合法");
        }
        rule.setStatus(targetStatus);
        priceRuleMapper.updateById(rule);
        return get(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> setDefault(Long id)
    {
        EmsPriceRule rule = requireRule(id);
        if (!"ENABLED".equalsIgnoreCase(rule.getStatus()))
        {
            throw new ServiceException("只有启用的电价规则可以设为默认");
        }
        rule.setIsDefault("0");
        priceRuleMapper.updateById(rule);
        clearOtherDefaultRules(rule.getTenantId(), rule.getId(), rule.getCompanyId(), rule.getPriceType());
        return get(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initDefaultsForCompany(Long tenantId, Long companyId)
    {
        if (tenantId == null || companyId == null || companyId <= 0)
        {
            throw new ServiceException("初始化默认电价时租户和公司不能为空");
        }
        initDefaultRule(tenantId, companyId, "PURCHASE", "默认购电电价");
        initDefaultRule(tenantId, companyId, "FEED_IN", "默认上网电价");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initDefaultAppliesForStation(Long tenantId, Long companyId, Long stationId)
    {
        Map<String, Object> station = requireStation(stationId);
        if (!Objects.equals(tenantId, EmsRequestSupport.asLong(station.get("tenantId"))))
        {
            throw new ServiceException("电站与目标租户不匹配");
        }
        initDefaultApplyForStation(tenantId, companyId, stationId, "PURCHASE");
        initDefaultApplyForStation(tenantId, companyId, stationId, "FEED_IN");
    }

    @Override
    public List<Map<String, Object>> listBindableRules(Long stationId, String priceType)
    {
        Map<String, Object> stationDetail = requireStation(stationId);
        Long stationCompanyId = EmsRequestSupport.asLong(stationDetail.get("companyId"));
        Long tenantId = EmsRequestSupport.asLong(stationDetail.get("tenantId"));
        LambdaQueryWrapper<EmsPriceRule> wrapper = new LambdaQueryWrapper<EmsPriceRule>()
                .eq(EmsPriceRule::getTenantId, tenantId)
                .eq(EmsPriceRule::getStatus, "ENABLED")
                .eq(EmsPriceRule::getDelFlag, "0")
                .and(w -> w.eq(EmsPriceRule::getCompanyId, 0L)
                        .or()
                        .eq(EmsPriceRule::getCompanyId, stationCompanyId == null ? -1L : stationCompanyId));
        if (StringUtils.isNotEmpty(priceType))
        {
            wrapper.eq(EmsPriceRule::getPriceType, priceType);
        }
        wrapper.orderByDesc(EmsPriceRule::getId);
        List<EmsPriceRule> rules = priceRuleMapper.selectList(wrapper);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (EmsPriceRule rule : rules)
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("id", rule.getId());
            row.put("ruleName", rule.getRuleName());
            row.put("priceType", rule.getPriceType());
            row.put("priceMode", rule.getPriceMode());
            row.put("basePrice", rule.getBasePrice());
            row.put("companyId", rule.getCompanyId());
            row.put("scopeLevel", rule.getCompanyId() != null && rule.getCompanyId() > 0 ? "COMPANY" : "TENANT");
            rows.add(row);
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> listStationBindings(Long stationId)
    {
        Map<String, Object> station = requireStation(stationId);
        Long tenantId = EmsRequestSupport.asLong(station.get("tenantId"));
        List<EmsPriceApply> applies = priceApplyMapper.selectList(new LambdaQueryWrapper<EmsPriceApply>()
                .eq(EmsPriceApply::getTenantId, tenantId)
                .eq(EmsPriceApply::getStationId, stationId)
                .eq(EmsPriceApply::getDelFlag, "0")
                .orderByDesc(EmsPriceApply::getEffectiveStart, EmsPriceApply::getId));
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (EmsPriceApply apply : applies)
        {
            EmsPriceRule rule = priceRuleMapper.selectById(apply.getRuleId());
            rows.add(buildBindingRow(apply, rule));
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveStationBinding(Map<String, Object> body)
    {
        Long stationId = EmsRequestSupport.requiredLong(body, "stationId", "电站不能为空");
        Map<String, Object> stationDetail = requireStation(stationId);
        Long stationCompanyId = EmsRequestSupport.asLong(stationDetail.get("companyId"));
        Long tenantId = EmsRequestSupport.asLong(stationDetail.get("tenantId"));
        Long ruleId = EmsRequestSupport.requiredLong(body, "ruleId", "电价规则不能为空");
        EmsPriceRule rule = priceRuleMapper.selectById(ruleId);
        if (rule == null || !"0".equals(rule.getDelFlag()) || !Objects.equals(rule.getTenantId(), tenantId))
        {
            throw new ServiceException("电价规则不存在");
        }
        if (rule.getCompanyId() != null && rule.getCompanyId() != 0L && !rule.getCompanyId().equals(stationCompanyId))
        {
            throw new ServiceException("该电价规则对当前电站不可见");
        }
        if (!"ENABLED".equalsIgnoreCase(rule.getStatus()))
        {
            throw new ServiceException("停用或过期的电价规则不能绑定");
        }
        String permanent = EmsRequestSupport.defaultString(body.get("permanent"), "0");
        Date effectiveStart = EmsRequestSupport.nullableTimestamp(body.get("effectiveStart"));
        if (effectiveStart == null)
        {
            throw new ServiceException("生效开始时间不能为空");
        }
        Date effectiveEnd = normalizeEffectiveEnd(body, permanent);
        validateStationBindingConflict(tenantId, stationId, rule.getPriceType(), effectiveStart, effectiveEnd, permanent);

        EmsPriceApply apply = new EmsPriceApply();
        apply.setTenantId(tenantId);
        apply.setCompanyId(stationCompanyId == null ? 0L : stationCompanyId);
        apply.setStationId(stationId);
        apply.setRuleId(ruleId);
        apply.setPriceType(rule.getPriceType());
        apply.setEffectiveStart(effectiveStart);
        apply.setEffectiveEnd(effectiveEnd);
        apply.setPermanent(permanent);
        apply.setStatus("ENABLED");
        apply.setRemark(EmsRequestSupport.stringValue(body.get("remark")));
        priceApplyMapper.insert(apply);
        return buildBindingRow(apply, rule);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeStationBinding(Long applyId)
    {
        EmsPriceApply apply = priceApplyMapper.selectById(applyId);
        if (apply == null)
        {
            throw new ServiceException("绑定记录不存在");
        }
        Map<String, Object> station = requireStation(apply.getStationId());
        if (!Objects.equals(apply.getTenantId(), EmsRequestSupport.asLong(station.get("tenantId"))))
        {
            throw new ServiceException("绑定记录与电站租户不匹配");
        }
        return priceApplyMapper.deleteById(applyId) > 0;
    }

    private Map<String, Object> requireStation(Long stationId)
    {
        if (stationId == null)
        {
            throw new ServiceException("电站不能为空");
        }
        Map<String, Object> stationDetail = stationMapper.selectStationDetail(stationId, authScopeService.currentScope());
        if (stationDetail == null || stationDetail.isEmpty())
        {
            throw new ServiceException("电站超出当前授权范围");
        }
        return stationDetail;
    }

    private Map<String, Object> buildBindingRow(EmsPriceApply apply, EmsPriceRule rule)
    {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", apply.getId());
        row.put("stationId", apply.getStationId());
        row.put("ruleId", apply.getRuleId());
        row.put("ruleName", rule == null ? null : rule.getRuleName());
        row.put("priceType", apply.getPriceType());
        row.put("priceMode", rule == null ? null : rule.getPriceMode());
        row.put("effectiveStart", apply.getEffectiveStart());
        row.put("effectiveEnd", apply.getEffectiveEnd());
        row.put("permanent", apply.getPermanent());
        row.put("status", apply.getStatus());
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean remove(Long id)
    {
        pricePeriodMapper.delete(new LambdaQueryWrapper<EmsPricePeriod>().eq(EmsPricePeriod::getRuleId, id));
        priceApplyMapper.delete(new LambdaQueryWrapper<EmsPriceApply>().eq(EmsPriceApply::getRuleId, id));
        return priceRuleMapper.deleteById(id) > 0;
    }

    private EmsPriceRule requireRule(Long id)
    {
        Map<String, Object> detail = priceRuleMapper.selectPriceDetail(id, authScopeService.currentScope());
        if (detail == null || detail.isEmpty())
        {
            throw new ServiceException("电价规则不存在或超出当前权限范围");
        }
        EmsPriceRule rule = priceRuleMapper.selectById(id);
        if (rule == null)
        {
            throw new ServiceException("电价规则不存在");
        }
        return rule;
    }

    private void initDefaultRule(Long tenantId, Long companyId, String priceType, String ruleName)
    {
        Long count = priceRuleMapper.selectCount(new LambdaQueryWrapper<EmsPriceRule>()
                .eq(EmsPriceRule::getTenantId, tenantId)
                .eq(EmsPriceRule::getCompanyId, companyId)
                .eq(EmsPriceRule::getPriceType, priceType)
                .eq(EmsPriceRule::getIsDefault, "0")
                .eq(EmsPriceRule::getDelFlag, "0"));
        if (count != null && count > 0)
        {
            return;
        }
        EmsPriceRule rule = new EmsPriceRule();
        rule.setTenantId(tenantId);
        rule.setCompanyId(companyId);
        rule.setRuleName(ruleName);
        rule.setPriceType(priceType);
        rule.setPriceMode("SIMPLE");
        rule.setCurrency("CNY");
        rule.setBasePrice(BigDecimal.ZERO);
        rule.setIsDefault("0");
        rule.setStatus("ENABLED");
        priceRuleMapper.insert(rule);
    }

    private void initDefaultApplyForStation(Long tenantId, Long companyId, Long stationId, String priceType)
    {
        Long existing = priceApplyMapper.selectCount(new LambdaQueryWrapper<EmsPriceApply>()
                .eq(EmsPriceApply::getTenantId, tenantId)
                .eq(EmsPriceApply::getStationId, stationId)
                .eq(EmsPriceApply::getPriceType, priceType)
                .eq(EmsPriceApply::getDelFlag, "0"));
        if (existing != null && existing > 0)
        {
            return;
        }
        EmsPriceRule rule = findDefaultRule(tenantId, companyId, priceType);
        if (rule == null)
        {
            // Backfill defaults for companies created before automatic price initialization was available.
            initDefaultRule(tenantId, companyId, priceType, "默认" + ("PURCHASE".equals(priceType) ? "购电" : "上网") + "电价");
            rule = findDefaultRule(tenantId, companyId, priceType);
        }
        if (rule == null)
        {
            throw new ServiceException("未配置可用的" + ("PURCHASE".equals(priceType) ? "购电" : "上网") + "默认电价");
        }
        EmsPriceApply apply = new EmsPriceApply();
        apply.setTenantId(tenantId);
        apply.setCompanyId(companyId);
        apply.setStationId(stationId);
        apply.setRuleId(rule.getId());
        apply.setPriceType(priceType);
        apply.setEffectiveStart(new Date());
        apply.setPermanent("0");
        apply.setStatus("ENABLED");
        priceApplyMapper.insert(apply);
    }

    private EmsPriceRule findDefaultRule(Long tenantId, Long companyId, String priceType)
    {
        EmsPriceRule companyRule = priceRuleMapper.selectOne(new LambdaQueryWrapper<EmsPriceRule>()
                .eq(EmsPriceRule::getTenantId, tenantId)
                .eq(EmsPriceRule::getCompanyId, companyId)
                .eq(EmsPriceRule::getPriceType, priceType)
                .eq(EmsPriceRule::getIsDefault, "0")
                .eq(EmsPriceRule::getStatus, "ENABLED")
                .eq(EmsPriceRule::getDelFlag, "0")
                .orderByDesc(EmsPriceRule::getId)
                .last("limit 1"));
        if (companyRule != null)
        {
            return companyRule;
        }
        return priceRuleMapper.selectOne(new LambdaQueryWrapper<EmsPriceRule>()
                .eq(EmsPriceRule::getTenantId, tenantId)
                .eq(EmsPriceRule::getCompanyId, 0L)
                .eq(EmsPriceRule::getPriceType, priceType)
                .eq(EmsPriceRule::getIsDefault, "0")
                .eq(EmsPriceRule::getStatus, "ENABLED")
                .eq(EmsPriceRule::getDelFlag, "0")
                .orderByDesc(EmsPriceRule::getId)
                .last("limit 1"));
    }

    private List<Long> listRuleStationIds(Long ruleId)
    {
        List<EmsPriceApply> applies = priceApplyMapper.selectList(new LambdaQueryWrapper<EmsPriceApply>()
                .eq(EmsPriceApply::getRuleId, ruleId)
                .eq(EmsPriceApply::getDelFlag, "0")
                .orderByAsc(EmsPriceApply::getStationId, EmsPriceApply::getId));
        List<Long> stationIds = new ArrayList<Long>();
        for (EmsPriceApply apply : applies)
        {
            if (apply.getStationId() != null && apply.getStationId() > 0)
            {
                stationIds.add(apply.getStationId());
            }
        }
        return stationIds;
    }

    private EmsPriceApply firstApply(Long ruleId)
    {
        return priceApplyMapper.selectOne(new LambdaQueryWrapper<EmsPriceApply>()
                .eq(EmsPriceApply::getRuleId, ruleId)
                .eq(EmsPriceApply::getDelFlag, "0")
                .orderByAsc(EmsPriceApply::getStationId, EmsPriceApply::getId)
                .last("limit 1"));
    }

    private void replacePeriods(Long tenantId, Long ruleId, Object periodsValue)
    {
        pricePeriodMapper.delete(new LambdaQueryWrapper<EmsPricePeriod>().eq(EmsPricePeriod::getRuleId, ruleId));
        if (!(periodsValue instanceof List))
        {
            return;
        }
        int sortNo = 1;
        for (Object item : (List<?>) periodsValue)
        {
            if (!(item instanceof Map))
            {
                continue;
            }
            Map<?, ?> row = (Map<?, ?>) item;
            String periodType = EmsRequestSupport.defaultString(row.get("periodType"), "PEAK");
            if (!ALLOWED_PERIOD_TYPES.contains(periodType))
            {
                throw new ServiceException("存在不合法的时段类型");
            }
            EmsPricePeriod period = new EmsPricePeriod();
            period.setTenantId(tenantId);
            period.setRuleId(ruleId);
            period.setPeriodName(EmsRequestSupport.stringValue(row.get("periodName")));
            period.setPeriodType(periodType);
            period.setStartTime(EmsRequestSupport.stringValue(row.get("startTime")));
            period.setEndTime(EmsRequestSupport.stringValue(row.get("endTime")));
            period.setPriceValue(EmsRequestSupport.asBigDecimal(row.containsKey("priceValue") ? row.get("priceValue") : row.get("price")));
            period.setWeekdayMask(EmsRequestSupport.defaultString(row.get("weekdayMask"), "1111111"));
            period.setSortNo(sortNo++);
            pricePeriodMapper.insert(period);
        }
    }

    private void replaceApplies(Long tenantId, Long ruleId, Long companyId, String priceType,
                               Date effectiveStart, Date effectiveEnd,
                               String permanent, List<Long> stationIds, Map<Long, Long> stationCompanyMap, Map<String, Object> body)
    {
        priceApplyMapper.delete(new LambdaQueryWrapper<EmsPriceApply>().eq(EmsPriceApply::getRuleId, ruleId));
        if (stationIds.isEmpty())
        {
            EmsPriceApply apply = buildApply(tenantId, ruleId, companyId, 0L, priceType,
                    effectiveStart, effectiveEnd, permanent, body);
            priceApplyMapper.insert(apply);
            return;
        }
        for (Long stationId : stationIds)
        {
            Long applyCompanyId = stationCompanyMap != null && stationCompanyMap.get(stationId) != null
                    ? stationCompanyMap.get(stationId)
                    : companyId;
            EmsPriceApply apply = buildApply(tenantId, ruleId, applyCompanyId, stationId, priceType,
                    effectiveStart, effectiveEnd, permanent, body);
            priceApplyMapper.insert(apply);
        }
    }

    private EmsPriceApply buildApply(Long tenantId, Long ruleId, Long companyId, Long stationId, String priceType,
                                     Date effectiveStart, Date effectiveEnd, String permanent, Map<String, Object> body)
    {
        EmsPriceApply apply = new EmsPriceApply();
        apply.setTenantId(tenantId);
        apply.setCompanyId(companyId);
        apply.setStationId(stationId);
        apply.setRuleId(ruleId);
        apply.setPriceType(priceType);
        apply.setEffectiveStart(effectiveStart);
        apply.setEffectiveEnd(effectiveEnd);
        apply.setPermanent(permanent);
        apply.setStatus("ENABLED");
        apply.setRemark(EmsRequestSupport.stringValue(body.get("remark")));
        return apply;
    }

    private Map<String, Object> validateCompany(Long companyId)
    {
        Map<String, Object> companyDetail = companyMapper.selectCompanyDetail(companyId, authScopeService.currentScope());
        if (companyDetail == null || companyDetail.isEmpty())
        {
            throw new ServiceException("公司超出当前授权范围");
        }
        return companyDetail;
    }

    private Map<Long, Long> resolveStationCompanies(Long ruleCompanyId, List<Long> stationIds)
    {
        boolean tenantLevel = ruleCompanyId == null || ruleCompanyId == 0L;
        Map<Long, Long> map = new LinkedHashMap<Long, Long>();
        for (Long stationId : stationIds)
        {
            Map<String, Object> stationDetail = stationMapper.selectStationDetail(stationId, authScopeService.currentScope());
            if (stationDetail == null || stationDetail.isEmpty())
            {
                throw new ServiceException("目标电站超出当前授权范围");
            }
            Long stationCompanyId = EmsRequestSupport.asLong(stationDetail.get("companyId"));
            if (!tenantLevel && stationCompanyId != null && !stationCompanyId.equals(ruleCompanyId))
            {
                throw new ServiceException("目标电站与规则所属公司不匹配");
            }
            map.put(stationId, stationCompanyId == null ? ruleCompanyId : stationCompanyId);
        }
        return map;
    }

    private List<Long> normalizeApplyStationIds(Map<String, Object> body)
    {
        List<Long> stationIds = EmsRequestSupport.asLongList(body.get("applyStationIds"));
        Long stationId = EmsRequestSupport.asLong(body.get("stationId"));
        if (stationIds.isEmpty() && stationId != null && stationId > 0)
        {
            stationIds.add(stationId);
        }
        return stationIds;
    }

    private Date normalizeEffectiveEnd(Map<String, Object> body, String permanent)
    {
        Date effectiveEnd = EmsRequestSupport.nullableTimestamp(body.get("effectiveEnd"));
        if ("0".equals(permanent))
        {
            return null;
        }
        if (effectiveEnd == null)
        {
            throw new ServiceException("非永久生效时必须填写结束时间");
        }
        Date effectiveStart = EmsRequestSupport.nullableTimestamp(body.get("effectiveStart"));
        if (effectiveStart != null && !effectiveEnd.after(effectiveStart))
        {
            throw new ServiceException("生效结束时间必须大于开始时间");
        }
        return effectiveEnd;
    }

    private void validatePeriods(Object periodsValue)
    {
        if (!(periodsValue instanceof List) || ((List<?>) periodsValue).isEmpty())
        {
            throw new ServiceException("至少需要一个分时时段");
        }
        List<Map<String, Object>> normalizedPeriods = new ArrayList<Map<String, Object>>();
        for (Object item : (List<?>) periodsValue)
        {
            if (!(item instanceof Map))
            {
                throw new ServiceException("分时时段数据不合法");
            }
            Map<?, ?> row = (Map<?, ?>) item;
            String startTime = EmsRequestSupport.stringValue(row.get("startTime"));
            String endTime = EmsRequestSupport.stringValue(row.get("endTime"));
            if (StringUtils.isEmpty(startTime) || StringUtils.isEmpty(endTime))
            {
                throw new ServiceException("分时时段开始和结束时间不能为空");
            }
            if (parseTime(startTime) >= parseTime(endTime))
            {
                throw new ServiceException("分时时段结束时间必须大于开始时间");
            }
            BigDecimal priceValue = EmsRequestSupport.asBigDecimal(row.containsKey("priceValue") ? row.get("priceValue") : row.get("price"));
            if (priceValue == null || priceValue.compareTo(BigDecimal.ZERO) < 0)
            {
                throw new ServiceException("分时时段价格必须大于等于0");
            }
            String weekdayMask = EmsRequestSupport.defaultString(row.get("weekdayMask"), "1111111");
            if (weekdayMask.length() != 7 || !weekdayMask.matches("[01]{7}"))
            {
                throw new ServiceException("星期掩码必须是7位01字符串");
            }
            Map<String, Object> normalized = new LinkedHashMap<String, Object>();
            normalized.put("startSeconds", parseTime(startTime));
            normalized.put("endSeconds", parseTime(endTime));
            normalized.put("weekdayMask", weekdayMask);
            normalizedPeriods.add(normalized);
        }
        normalizedPeriods.sort(Comparator.comparingInt(item -> (Integer) item.get("startSeconds")));
        for (int i = 0; i < normalizedPeriods.size(); i++)
        {
            Map<String, Object> current = normalizedPeriods.get(i);
            for (int j = i + 1; j < normalizedPeriods.size(); j++)
            {
                Map<String, Object> next = normalizedPeriods.get(j);
                if (!weekdayOverlap(String.valueOf(current.get("weekdayMask")), String.valueOf(next.get("weekdayMask"))))
                {
                    continue;
                }
                int currentEnd = (Integer) current.get("endSeconds");
                int nextStart = (Integer) next.get("startSeconds");
                if (nextStart < currentEnd)
                {
                    throw new ServiceException("分时时段存在重叠，请检查时间范围");
                }
            }
        }
    }

    private void validateApplyConflict(Long tenantId, Long currentRuleId, Long companyId, List<Long> stationIds, String priceType,
                                       Date effectiveStart, Date effectiveEnd, String permanent, String status)
    {
        if (!"ENABLED".equalsIgnoreCase(status))
        {
            return;
        }
        List<EmsPriceApply> applies = priceApplyMapper.selectList(new LambdaQueryWrapper<EmsPriceApply>()
                .eq(EmsPriceApply::getTenantId, tenantId)
                .eq(EmsPriceApply::getCompanyId, companyId)
                .eq(EmsPriceApply::getPriceType, priceType)
                .eq(EmsPriceApply::getDelFlag, "0"));
        for (EmsPriceApply apply : applies)
        {
            if (apply == null)
            {
                continue;
            }
            if (currentRuleId != null && currentRuleId.equals(apply.getRuleId()))
            {
                continue;
            }
            EmsPriceRule existsRule = priceRuleMapper.selectById(apply.getRuleId());
            if (existsRule == null || !"0".equals(existsRule.getDelFlag()) || !"ENABLED".equalsIgnoreCase(existsRule.getStatus()))
            {
                continue;
            }
            if (timeRangeOverlap(effectiveStart, effectiveEnd, permanent, apply.getEffectiveStart(), apply.getEffectiveEnd(), apply.getPermanent()))
            {
                throw new ServiceException("同一公司下相同电价类型在同一时间仅允许存在一套启用规则");
            }
        }
    }

    private void validateStationBindingConflict(Long tenantId, Long stationId, String priceType,
                                                Date effectiveStart, Date effectiveEnd, String permanent)
    {
        List<EmsPriceApply> applies = priceApplyMapper.selectList(new LambdaQueryWrapper<EmsPriceApply>()
                .eq(EmsPriceApply::getTenantId, tenantId)
                .eq(EmsPriceApply::getStationId, stationId)
                .eq(EmsPriceApply::getPriceType, priceType)
                .eq(EmsPriceApply::getDelFlag, "0")
                .eq(EmsPriceApply::getStatus, "ENABLED"));
        for (EmsPriceApply apply : applies)
        {
            EmsPriceRule existsRule = apply == null ? null : priceRuleMapper.selectById(apply.getRuleId());
            if (existsRule == null || !"0".equals(existsRule.getDelFlag()) || !"ENABLED".equalsIgnoreCase(existsRule.getStatus()))
            {
                continue;
            }
            if (timeRangeOverlap(effectiveStart, effectiveEnd, permanent, apply.getEffectiveStart(), apply.getEffectiveEnd(), apply.getPermanent()))
            {
                throw new ServiceException("同一电站下相同电价类型的生效时间不能重叠");
            }
        }
    }

    private boolean timeRangeOverlap(Date start1, Date end1, String permanent1, Date start2, Date end2, String permanent2)
    {
        long left1 = start1 == null ? Long.MIN_VALUE : start1.getTime();
        long right1 = "0".equals(permanent1) || end1 == null ? Long.MAX_VALUE : end1.getTime();
        long left2 = start2 == null ? Long.MIN_VALUE : start2.getTime();
        long right2 = "0".equals(permanent2) || end2 == null ? Long.MAX_VALUE : end2.getTime();
        return left1 <= right2 && left2 <= right1;
    }

    private void clearOtherDefaultRules(Long tenantId, Long currentRuleId, Long companyId, String priceType)
    {
        List<EmsPriceRule> rules = priceRuleMapper.selectList(new LambdaQueryWrapper<EmsPriceRule>()
                .eq(EmsPriceRule::getTenantId, tenantId)
                .eq(EmsPriceRule::getCompanyId, companyId)
                .eq(EmsPriceRule::getPriceType, priceType)
                .eq(EmsPriceRule::getIsDefault, "0")
                .eq(EmsPriceRule::getDelFlag, "0"));
        for (EmsPriceRule item : rules)
        {
            if (item == null || item.getId() == null || item.getId().equals(currentRuleId))
            {
                continue;
            }
            item.setIsDefault("1");
            priceRuleMapper.updateById(item);
        }
    }

    private BigDecimal defaultPrice(Object value)
    {
        BigDecimal price = EmsRequestSupport.asBigDecimal(value);
        return price == null ? BigDecimal.ZERO : price;
    }

    private int parseTime(String value)
    {
        try
        {
            LocalTime parsed = LocalTime.parse(value);
            return parsed.toSecondOfDay();
        }
        catch (DateTimeParseException e)
        {
            throw new ServiceException("分时时段时间格式必须为 HH:mm:ss");
        }
    }

    private boolean weekdayOverlap(String left, String right)
    {
        for (int i = 0; i < 7; i++)
        {
            if (left.charAt(i) == '1' && right.charAt(i) == '1')
            {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> queryMap(Map<String, String> query)
    {
        return query == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(query);
    }
}
