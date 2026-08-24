package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.domain.entity.EmsPriceApply;
import com.witos.ems.server.domain.entity.EmsPriceRule;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsPriceApplyMapper;
import com.witos.ems.server.mapper.EmsPricePeriodMapper;
import com.witos.ems.server.mapper.EmsPriceRuleMapper;
import com.witos.ems.server.mapper.EmsStationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsPriceServiceImplTest
{
    @Mock
    private EmsPriceRuleMapper priceRuleMapper;

    @Mock
    private EmsPricePeriodMapper pricePeriodMapper;

    @Mock
    private EmsPriceApplyMapper priceApplyMapper;

    @Mock
    private EmsCompanyMapper companyMapper;

    @Mock
    private EmsStationMapper stationMapper;

    @Mock
    private EmsAuthScopeService authScopeService;

    @InjectMocks
    private EmsPriceServiceImpl service;

    @Test
    void repeatedDefaultInitializationCreatesOnlyPurchaseAndFeedInRulesOnce()
    {
        when(priceRuleMapper.selectCount(any(Wrapper.class))).thenReturn(0L, 0L, 1L, 1L);

        service.initDefaultsForCompany(22L, 33L);
        service.initDefaultsForCompany(22L, 33L);

        ArgumentCaptor<EmsPriceRule> rules = ArgumentCaptor.forClass(EmsPriceRule.class);
        verify(priceRuleMapper, times(2)).insert(rules.capture());
        List<EmsPriceRule> inserted = rules.getAllValues();
        assertEquals(Arrays.asList("PURCHASE", "FEED_IN"),
                Arrays.asList(inserted.get(0).getPriceType(), inserted.get(1).getPriceType()));
        assertEquals("SIMPLE", inserted.get(0).getPriceMode());
        assertEquals("CNY", inserted.get(1).getCurrency());
    }

    @Test
    void stationInitializationCreatesMissingDefaultPricesAndBindings()
    {
        Map<String, Object> station = new LinkedHashMap<String, Object>();
        station.put("stationId", 44L);
        station.put("tenantId", 22L);
        when(stationMapper.selectStationDetail(any(), any())).thenReturn(station);
        when(priceApplyMapper.selectCount(any(Wrapper.class))).thenReturn(0L, 0L);
        when(priceRuleMapper.selectCount(any(Wrapper.class))).thenReturn(0L, 0L);
        org.mockito.Mockito.doAnswer(invocation -> {
            EmsPriceRule rule = invocation.getArgument(0);
            rule.setId("PURCHASE".equals(rule.getPriceType()) ? 101L : 102L);
            return 1;
        }).when(priceRuleMapper).insert(any(EmsPriceRule.class));
        EmsPriceRule purchaseRule = new EmsPriceRule();
        purchaseRule.setId(101L);
        purchaseRule.setPriceType("PURCHASE");
        EmsPriceRule feedInRule = new EmsPriceRule();
        feedInRule.setId(102L);
        feedInRule.setPriceType("FEED_IN");
        when(priceRuleMapper.selectOne(any(Wrapper.class)))
                .thenReturn(null)
                .thenReturn(null)
                .thenReturn(purchaseRule)
                .thenReturn(null)
                .thenReturn(null)
                .thenReturn(feedInRule);

        service.initDefaultAppliesForStation(22L, 33L, 44L);

        verify(priceRuleMapper, times(2)).insert(any(EmsPriceRule.class));
        verify(priceApplyMapper, times(2)).insert(any(EmsPriceApply.class));
    }
}
