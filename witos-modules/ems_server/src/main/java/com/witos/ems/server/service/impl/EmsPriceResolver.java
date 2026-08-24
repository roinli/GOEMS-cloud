package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.witos.ems.server.domain.entity.EmsPriceApply;
import com.witos.ems.server.domain.entity.EmsPricePeriod;
import com.witos.ems.server.domain.entity.EmsPriceRule;
import com.witos.ems.server.domain.entity.EmsStation;
import com.witos.ems.server.mapper.EmsPriceApplyMapper;
import com.witos.ems.server.mapper.EmsPricePeriodMapper;
import com.witos.ems.server.mapper.EmsPriceRuleMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import com.witos.common.core.exception.ServiceException;
import com.witos.ems.server.support.EmsRequestSupport;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

@Service
public class EmsPriceResolver
{
    @Resource
    private EmsPriceApplyMapper priceApplyMapper;

    @Resource
    private EmsPriceRuleMapper priceRuleMapper;

    @Resource
    private EmsPricePeriodMapper pricePeriodMapper;

    @Resource
    private EmsStationMapper stationMapper;

    public BigDecimal resolveRevenue(Long companyId, Long stationId, BigDecimal energyKwh, Date statTime)
    {
        BigDecimal generation = energyKwh == null ? BigDecimal.ZERO : energyKwh;
        if (generation.compareTo(BigDecimal.ZERO) <= 0)
        {
            return BigDecimal.ZERO;
        }
        BigDecimal unitPrice = resolveUnitPrice(companyId, stationId, "FEED_IN", statTime);
        return generation.multiply(unitPrice).setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    public RevenueBreakdown resolveRevenueBreakdown(Long companyId, Long stationId, BigDecimal generationKwh,
                                                    BigDecimal gridExportKwh, BigDecimal chargeKwh,
                                                    BigDecimal dischargeKwh, BigDecimal gridImportKwh,
                                                    Date statTime, boolean strict)
    {
        return resolveRevenueBreakdown(EmsRequestSupport.currentTenantId(), companyId, stationId,
                generationKwh, gridExportKwh, chargeKwh, dischargeKwh, gridImportKwh, statTime, strict);
    }

    public RevenueBreakdown resolveRevenueBreakdown(Long tenantId, Long companyId, Long stationId,
                                                    BigDecimal generationKwh, BigDecimal gridExportKwh,
                                                    BigDecimal chargeKwh, BigDecimal dischargeKwh,
                                                    BigDecimal gridImportKwh, Date statTime, boolean strict)
    {
        BigDecimal generation = defaultDecimal(generationKwh);
        BigDecimal gridExport = defaultDecimal(gridExportKwh);
        BigDecimal charge = defaultDecimal(chargeKwh);
        BigDecimal discharge = defaultDecimal(dischargeKwh);
        BigDecimal gridImport = defaultDecimal(gridImportKwh);
        BigDecimal feedInPrice = strict
                ? resolveRequiredUnitPrice(tenantId, companyId, stationId, "FEED_IN", statTime, "上网电价")
                : resolveUnitPrice(tenantId, companyId, stationId, "FEED_IN", statTime);
        BigDecimal purchasePrice = strict
                ? resolveRequiredUnitPrice(tenantId, companyId, stationId, "PURCHASE", statTime, "购电电价")
                : resolveUnitPrice(tenantId, companyId, stationId, "PURCHASE", statTime);

        BigDecimal selfUseKwh = generation.subtract(gridExport).max(BigDecimal.ZERO);
        BigDecimal feedInRevenue = gridExport.multiply(feedInPrice);
        BigDecimal selfUseSaving = selfUseKwh.multiply(purchasePrice);
        BigDecimal storageArbitrageRevenue = discharge.multiply(purchasePrice).subtract(charge.multiply(purchasePrice));
        BigDecimal purchaseCost = gridImport.multiply(purchasePrice);
        BigDecimal revenueAmount = feedInRevenue.add(selfUseSaving).add(storageArbitrageRevenue);
        RevenueBreakdown breakdown = new RevenueBreakdown();
        breakdown.setRevenueAmount(scaleMoney(revenueAmount));
        breakdown.setFeedInRevenue(scaleMoney(feedInRevenue));
        breakdown.setSelfUseSaving(scaleMoney(selfUseSaving));
        breakdown.setStorageArbitrageRevenue(scaleMoney(storageArbitrageRevenue));
        breakdown.setPurchaseCost(scaleMoney(purchaseCost));
        breakdown.setQualityReason("收益按上网收益、自发自用节省和储能峰谷套利估算；购电成本单列，不从总收益扣除");
        return breakdown;
    }

    private BigDecimal resolveRequiredUnitPrice(Long tenantId, Long companyId, Long stationId, String priceType, Date statTime, String label)
    {
        BigDecimal unitPrice = resolveUnitPrice(tenantId, companyId, stationId, priceType, statTime);
        if (unitPrice.compareTo(BigDecimal.ZERO) <= 0)
        {
            throw new ServiceException("收益计算缺少" + label + "配置");
        }
        return unitPrice;
    }

    private BigDecimal defaultDecimal(BigDecimal value)
    {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal scaleMoney(BigDecimal value)
    {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    public BigDecimal resolveUnitPrice(Long companyId, Long stationId, String priceType, Date statTime)
    {
        return resolveUnitPrice(EmsRequestSupport.currentTenantId(), companyId, stationId, priceType, statTime);
    }

    public BigDecimal resolveUnitPrice(Long tenantId, Long companyId, Long stationId, String priceType, Date statTime)
    {
        if (companyId == null || priceType == null)
        {
            return BigDecimal.ZERO;
        }
        Date evaluateTime = statTime == null ? new Date() : statTime;
        EmsPriceApply apply = matchApply(tenantId, companyId, stationId, priceType, evaluateTime);
        if (apply == null || apply.getRuleId() == null)
        {
            return BigDecimal.ZERO;
        }
        EmsPriceRule rule = priceRuleMapper.selectById(apply.getRuleId());
        if (rule == null || !"0".equals(rule.getDelFlag()))
        {
            return BigDecimal.ZERO;
        }
        BigDecimal basePrice = rule.getBasePrice() == null ? BigDecimal.ZERO : rule.getBasePrice();
        if ("SIMPLE".equalsIgnoreCase(rule.getPriceMode()))
        {
            return basePrice;
        }
        BigDecimal periodPrice = matchPeriodPrice(tenantId, rule.getId(), evaluateTime, resolveStationZoneId(stationId));
        return periodPrice == null ? basePrice : periodPrice;
    }

    private EmsPriceApply matchApply(Long tenantId, Long companyId, Long stationId, String priceType, Date statTime)
    {
        List<Long> scopeStationIds = stationId == null ? Collections.singletonList(0L) : Arrays.asList(stationId, 0L);
        List<EmsPriceApply> applies = priceApplyMapper.selectList(new LambdaQueryWrapper<EmsPriceApply>()
                .eq(EmsPriceApply::getTenantId, tenantId)
                .eq(EmsPriceApply::getCompanyId, companyId)
                .eq(EmsPriceApply::getPriceType, priceType)
                .in(EmsPriceApply::getStationId, scopeStationIds)
                .eq(EmsPriceApply::getDelFlag, "0")
                .orderByDesc(EmsPriceApply::getStationId)
                .orderByDesc(EmsPriceApply::getEffectiveStart)
                .orderByDesc(EmsPriceApply::getId));
        for (EmsPriceApply item : applies)
        {
            if (isApplyActive(item, statTime))
            {
                return item;
            }
        }
        return null;
    }

    private boolean isApplyActive(EmsPriceApply apply, Date statTime)
    {
        if (apply == null)
        {
            return false;
        }
        Date start = apply.getEffectiveStart();
        Date end = apply.getEffectiveEnd();
        if (start != null && statTime.before(start))
        {
            return false;
        }
        if (!"0".equals(apply.getPermanent()) && end != null && statTime.after(end))
        {
            return false;
        }
        return true;
    }

    private BigDecimal matchPeriodPrice(Long tenantId, Long ruleId, Date statTime, ZoneId zoneId)
    {
        List<EmsPricePeriod> periods = pricePeriodMapper.selectList(new LambdaQueryWrapper<EmsPricePeriod>()
                .eq(EmsPricePeriod::getTenantId, tenantId)
                .eq(EmsPricePeriod::getRuleId, ruleId)
                .orderByAsc(EmsPricePeriod::getSortNo, EmsPricePeriod::getId));
        if (periods.isEmpty())
        {
            return null;
        }
        LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(statTime.getTime()), zoneId);
        int weekdayIndex = weekdayIndex(dateTime.getDayOfWeek());
        LocalTime currentTime = dateTime.toLocalTime();
        for (EmsPricePeriod period : periods)
        {
            if (!matchesWeekday(period.getWeekdayMask(), weekdayIndex))
            {
                continue;
            }
            LocalTime start = parseTime(period.getStartTime());
            LocalTime end = parseTime(period.getEndTime());
            if (start == null || end == null)
            {
                continue;
            }
            if ((currentTime.equals(start) || currentTime.isAfter(start)) && currentTime.isBefore(end))
            {
                return period.getPriceValue();
            }
        }
        return null;
    }

    private ZoneId resolveStationZoneId(Long stationId)
    {
        if (stationId != null && stationId > 0)
        {
            EmsStation station = stationMapper.selectById(stationId);
            if (station != null && station.getTimezone() != null)
            {
                try
                {
                    String timezone = station.getTimezone().trim();
                    if (!timezone.isEmpty())
                    {
                        return ZoneId.of(timezone);
                    }
                }
                catch (Exception ignored)
                {
                    return ZoneId.systemDefault();
                }
            }
        }
        return ZoneId.systemDefault();
    }

    private boolean matchesWeekday(String weekdayMask, int weekdayIndex)
    {
        String mask = weekdayMask == null || weekdayMask.length() != 7 ? "1111111" : weekdayMask;
        return mask.charAt(weekdayIndex) == '1';
    }

    private int weekdayIndex(DayOfWeek dayOfWeek)
    {
        switch (dayOfWeek)
        {
            case MONDAY:
                return 0;
            case TUESDAY:
                return 1;
            case WEDNESDAY:
                return 2;
            case THURSDAY:
                return 3;
            case FRIDAY:
                return 4;
            case SATURDAY:
                return 5;
            default:
                return 6;
        }
    }

    private LocalTime parseTime(String value)
    {
        try
        {
            return LocalTime.parse(value);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    public static class RevenueBreakdown
    {
        private BigDecimal revenueAmount;
        private BigDecimal feedInRevenue;
        private BigDecimal selfUseSaving;
        private BigDecimal storageArbitrageRevenue;
        private BigDecimal purchaseCost;
        private String qualityReason;

        public BigDecimal getRevenueAmount()
        {
            return revenueAmount;
        }

        public void setRevenueAmount(BigDecimal revenueAmount)
        {
            this.revenueAmount = revenueAmount;
        }

        public BigDecimal getFeedInRevenue()
        {
            return feedInRevenue;
        }

        public void setFeedInRevenue(BigDecimal feedInRevenue)
        {
            this.feedInRevenue = feedInRevenue;
        }

        public BigDecimal getSelfUseSaving()
        {
            return selfUseSaving;
        }

        public void setSelfUseSaving(BigDecimal selfUseSaving)
        {
            this.selfUseSaving = selfUseSaving;
        }

        public BigDecimal getStorageArbitrageRevenue()
        {
            return storageArbitrageRevenue;
        }

        public void setStorageArbitrageRevenue(BigDecimal storageArbitrageRevenue)
        {
            this.storageArbitrageRevenue = storageArbitrageRevenue;
        }

        public BigDecimal getPurchaseCost()
        {
            return purchaseCost;
        }

        public void setPurchaseCost(BigDecimal purchaseCost)
        {
            this.purchaseCost = purchaseCost;
        }

        public String getQualityReason()
        {
            return qualityReason;
        }

        public void setQualityReason(String qualityReason)
        {
            this.qualityReason = qualityReason;
        }
    }
}
