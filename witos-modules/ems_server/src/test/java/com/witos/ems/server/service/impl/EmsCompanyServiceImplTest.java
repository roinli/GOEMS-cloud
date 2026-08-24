package com.witos.ems.server.service.impl;

import com.witos.ems.server.auth.EmsAuthScopeService;
import com.witos.ems.server.domain.entity.EmsCompany;
import com.witos.ems.server.mapper.EmsCompanyMapper;
import com.witos.ems.server.mapper.EmsUserProfileMapper;
import com.witos.ems.server.service.EmsBusinessConfigService;
import com.witos.ems.server.service.EmsPriceService;
import com.witos.common.core.context.SecurityContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsCompanyServiceImplTest
{
    @Mock
    private EmsCompanyMapper companyMapper;

    @Mock
    private EmsUserProfileMapper userProfileMapper;

    @Mock
    private EmsAuthScopeService authScopeService;

    @Mock
    private EmsBusinessConfigService businessConfigService;

    @Mock
    private EmsPriceService priceService;

    @InjectMocks
    private EmsCompanyServiceImpl service;

    @BeforeEach
    void setUpTenant()
    {
        SecurityContextHolder.setTenantId("1005");
    }

    @AfterEach
    void clearTenant()
    {
        SecurityContextHolder.remove();
    }

    @Test
    void createRunsProfileConfigAndPriceInitializationInOrder()
    {
        doAnswer(invocation -> {
            EmsCompany company = invocation.getArgument(0);
            company.setId(101L);
            return 1;
        }).when(companyMapper).insert(any(EmsCompany.class));
        when(companyMapper.selectCompanyDetail(eq(101L), any())).thenReturn(companyDetail(101L));

        Map<String, Object> result = service.save(companyBody(null));

        assertEquals(101L, result.get("companyId"));
        InOrder calls = inOrder(companyMapper, userProfileMapper, businessConfigService, priceService);
        calls.verify(companyMapper).insert(any(EmsCompany.class));
        calls.verify(userProfileMapper).bindFirstCompanyToDefaultAdmin(1005L, 101L);
        calls.verify(businessConfigService).bindCompanyDefaults(1005L, 101L);
        calls.verify(priceService).initDefaultsForCompany(1005L, 101L);
    }

    @Test
    void updateDoesNotRebindProfileOrInitializeDefaults()
    {
        when(companyMapper.selectCompanyDetail(eq(101L), any())).thenReturn(companyDetail(101L));
        EmsCompany current = new EmsCompany();
        current.setId(101L);
        when(companyMapper.selectById(101L)).thenReturn(current);

        service.save(companyBody(101L));

        verify(companyMapper).updateById(any(EmsCompany.class));
        verify(userProfileMapper, never()).bindFirstCompanyToDefaultAdmin(any(), any());
        verify(businessConfigService, never()).bindCompanyDefaults(any(), any());
        verify(priceService, never()).initDefaultsForCompany(any(), any());
    }

    @Test
    void rootUpdatePreservesExistingTenant()
    {
        SecurityContextHolder.setTenantId("9999");
        SecurityContextHolder.setUserId("1");
        when(companyMapper.selectCompanyDetail(eq(101L), any())).thenReturn(companyDetail(101L));
        EmsCompany current = new EmsCompany();
        current.setId(101L);
        current.setTenantId(1005L);
        when(companyMapper.selectById(101L)).thenReturn(current);

        service.save(companyBody(101L));

        ArgumentCaptor<EmsCompany> company = ArgumentCaptor.forClass(EmsCompany.class);
        verify(companyMapper).updateById(company.capture());
        assertEquals(1005L, company.getValue().getTenantId());
    }

    @Test
    void rootCreateChildInheritsParentTenant()
    {
        SecurityContextHolder.setTenantId("9999");
        SecurityContextHolder.setUserId("1");
        EmsCompany parent = new EmsCompany();
        parent.setId(88L);
        parent.setTenantId(1005L);
        parent.setAncestors("0");
        when(companyMapper.selectCompanyDetail(eq(88L), any())).thenReturn(companyDetail(88L));
        when(companyMapper.selectById(88L)).thenReturn(parent);
        doAnswer(invocation -> {
            EmsCompany company = invocation.getArgument(0);
            company.setId(102L);
            return 1;
        }).when(companyMapper).insert(any(EmsCompany.class));
        when(companyMapper.selectCompanyDetail(eq(102L), any())).thenReturn(companyDetail(102L));
        Map<String, Object> body = companyBody(null);
        body.put("parentId", 88L);

        service.save(body);

        ArgumentCaptor<EmsCompany> company = ArgumentCaptor.forClass(EmsCompany.class);
        verify(companyMapper).insert(company.capture());
        assertEquals(1005L, company.getValue().getTenantId());
    }

    private Map<String, Object> companyBody(Long companyId)
    {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("companyId", companyId);
        body.put("companyName", "首家公司");
        return body;
    }

    private Map<String, Object> companyDetail(Long companyId)
    {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("companyId", companyId);
        return detail;
    }
}
