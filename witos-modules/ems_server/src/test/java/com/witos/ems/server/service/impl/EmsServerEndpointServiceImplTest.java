package com.witos.ems.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.witos.common.core.context.SecurityContextHolder;
import com.witos.common.core.exception.ServiceException;
import com.witos.ems.server.domain.entity.EmsOpenemsEndpointSource;
import com.witos.ems.server.domain.entity.EmsServerEndpoint;
import com.witos.ems.server.mapper.EmsOpenemsEndpointSourceMapper;
import com.witos.ems.server.mapper.EmsServerEndpointMapper;
import com.witos.ems.server.openems.DefaultOpenemsJsonRpcClient;
import com.witos.ems.server.openems.OpenemsInfluxConnectionTester;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmsServerEndpointServiceImplTest
{
    private static final Long CURRENT_TENANT_ID = 1001L;

    @Mock
    private EmsServerEndpointMapper endpointMapper;

    @Mock
    private EmsOpenemsEndpointSourceMapper sourceMapper;

    @Mock
    private DefaultOpenemsJsonRpcClient openemsClient;

    @Mock
    private OpenemsInfluxConnectionTester influxConnectionTester;

    @InjectMocks
    private EmsServerEndpointServiceImpl service;

    @BeforeEach
    void setUpTenant()
    {
        SecurityContextHolder.setTenantId(String.valueOf(CURRENT_TENANT_ID));
    }

    @AfterEach
    void clearSecurityContext()
    {
        SecurityContextHolder.remove();
    }

    @Test
    void currentTenantCanAccessEndpointById()
    {
        EmsServerEndpoint endpoint = endpoint(CURRENT_TENANT_ID, "TENANT", "env:OPENEMS_TOKEN");
        when(endpointMapper.selectById(1L)).thenReturn(endpoint);

        Map<String, Object> view = service.get(1L);

        assertEquals(1L, view.get("id"));
        assertEquals("TENANT", view.get("scopeType"));
    }

    @Test
    void crossTenantEndpointIsRejected()
    {
        when(endpointMapper.selectById(1L)).thenReturn(endpoint(2002L, "TENANT", "env:OPENEMS_TOKEN"));

        ServiceException exception = assertThrows(ServiceException.class, () -> service.get(1L));

        assertEquals("端点不存在或无权访问", exception.getMessage());
    }

    @Test
    void nonTenantScopeIsRejected()
    {
        when(endpointMapper.selectById(1L)).thenReturn(endpoint(CURRENT_TENANT_ID, "GLOBAL", "env:OPENEMS_TOKEN"));

        ServiceException exception = assertThrows(ServiceException.class, () -> service.get(1L));

        assertEquals("端点不存在或无权访问", exception.getMessage());
    }

    @Test
    void viewRedactsCredentialReferenceAndOnlyReportsConfigurationState()
    {
        when(endpointMapper.selectById(1L)).thenReturn(endpoint(CURRENT_TENANT_ID, "TENANT", "env:OPENEMS_TOKEN"));

        Map<String, Object> view = service.get(1L);

        assertFalse(view.containsKey("credentialRef"));
        assertEquals(Boolean.TRUE, view.get("credentialConfigured"));
        assertEquals("ws://openems.example.com:8082", view.get("wsUrl"));
    }

    @Test
    void endpointAcceptsDirectCredentialWithoutReturningIt()
    {
        when(endpointMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        Map<String, Object> view = service.save(saveBody("direct-token"));

        assertEquals(Boolean.TRUE, view.get("credentialConfigured"));
        assertFalse(view.containsKey("credentialRef"));
    }

    @Test
    void productionAcceptsEnvironmentAndSystemCredentialReferences()
    {
        when(endpointMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        Map<String, Object> environmentView = service.save(saveBody("env:OPENEMS_TOKEN"));
        Map<String, Object> systemView = service.save(saveBody("sys:openems.token"));

        assertEquals(Boolean.TRUE, environmentView.get("credentialConfigured"));
        assertEquals(Boolean.TRUE, systemView.get("credentialConfigured"));
        assertFalse(environmentView.containsKey("credentialRef"));
        assertFalse(systemView.containsKey("credentialRef"));
    }

    @Test
    void newEndpointRequiresRawInfluxConfiguration()
    {
        Map<String, Object> body = saveBody("env:OPENEMS_TOKEN");
        body.remove("rawInflux");
        when(endpointMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        ServiceException exception = assertThrows(ServiceException.class, () -> service.save(body));

        assertEquals("Raw Influx配置不能为空", exception.getMessage());
    }

    @Test
    void invalidIanaTimezoneIsRejected()
    {
        Map<String, Object> body = saveBody("env:OPENEMS_TOKEN");
        body.put("defaultTimezone", "UTC+8");

        ServiceException exception = assertThrows(ServiceException.class, () -> service.save(body));

        assertEquals("默认时区必须是有效的IANA时区", exception.getMessage());
    }

    @Test
    void sourceCredentialReferencesAreNeverReturned()
    {
        EmsServerEndpoint endpoint = endpoint(CURRENT_TENANT_ID, "TENANT", "env:OPENEMS_TOKEN");
        EmsOpenemsEndpointSource raw = rawSource();
        when(endpointMapper.selectById(1L)).thenReturn(endpoint);
        when(sourceMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(raw));

        Map<String, Object> view = service.get(1L);
        Map<?, ?> rawView = (Map<?, ?>) view.get("rawInflux");

        assertEquals(Boolean.TRUE, rawView.get("credentialConfigured"));
        assertFalse(rawView.containsKey("credentialRef"));
    }

    @Test
    void connectionTestsKeepApiRawAndAggregatedResultsIndependent()
    {
        EmsServerEndpoint endpoint = endpoint(CURRENT_TENANT_ID, "TENANT", "env:OPENEMS_TOKEN");
        EmsOpenemsEndpointSource raw = rawSource();
        when(endpointMapper.selectById(1L)).thenReturn(endpoint);
        when(sourceMapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(raw));
        when(openemsClient.testConnection(endpoint)).thenReturn(testResult(false, "FAILED"));
        when(influxConnectionTester.test(raw)).thenReturn(testResult(true, "SUCCESS"));
        when(influxConnectionTester.test(null)).thenReturn(testResult(false, "NOT_CONFIGURED"));

        Map<String, Object> result = service.test(1L);

        assertEquals("FAILED", result.get("overallStatus"));
        assertEquals("FAILED", ((Map<?, ?>) result.get("api")).get("status"));
        assertEquals("SUCCESS", ((Map<?, ?>) result.get("rawInflux")).get("status"));
        assertEquals("NOT_CONFIGURED", ((Map<?, ?>) result.get("aggregatedInflux")).get("status"));
    }

    @Test
    void superAdministratorCanAccessTargetTenantEndpoint()
    {
        SecurityContextHolder.setUserId("1");
        when(endpointMapper.selectById(1L)).thenReturn(endpoint(2002L, "TENANT", "env:OPENEMS_TOKEN"));

        Map<String, Object> view = service.get(1L);

        assertEquals(1L, view.get("id"));
    }

    private EmsServerEndpoint endpoint(Long tenantId, String scopeType, String credentialRef)
    {
        EmsServerEndpoint endpoint = new EmsServerEndpoint();
        endpoint.setId(1L);
        endpoint.setTenantId(tenantId);
        endpoint.setScopeType(scopeType);
        endpoint.setEndpointCode("openems-main");
        endpoint.setEndpointName("OpenEMS Main");
        endpoint.setBaseUrl("ws://openems.example.com:8082");
        endpoint.setAuthType("BEARER");
        endpoint.setCredentialRef(credentialRef);
        endpoint.setEnabled("0");
        return endpoint;
    }

    private Map<String, Object> saveBody(String credentialRef)
    {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("endpointCode", "openems-main");
        body.put("endpointName", "OpenEMS Main");
        body.put("wsUrl", "ws://openems.example.com:8082");
        body.put("authType", "BEARER");
        body.put("credentialRef", credentialRef);
        body.put("enabled", "0");
        body.put("defaultTimezone", "Asia/Shanghai");
        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("url", "http://influx.example.com:8086");
        raw.put("version", "INFLUX_1");
        raw.put("databaseName", "openems");
        raw.put("measurement", "data");
        raw.put("edgeTag", "edge");
        raw.put("credentialRef", "env:INFLUX_CREDENTIAL");
        raw.put("enabled", "0");
        body.put("rawInflux", raw);
        return body;
    }

    private EmsOpenemsEndpointSource rawSource()
    {
        EmsOpenemsEndpointSource source = new EmsOpenemsEndpointSource();
        source.setId(11L);
        source.setTenantId(CURRENT_TENANT_ID);
        source.setEndpointId(1L);
        source.setSourceType("RAW_INFLUX");
        source.setUrl("http://influx.example.com:8086");
        source.setVersion("INFLUX_1");
        source.setQueryLanguage("INFLUXQL");
        source.setDatabaseName("openems");
        source.setBucket("openems");
        source.setMeasurement("data");
        source.setEdgeTag("edge");
        source.setTimezone("Asia/Shanghai");
        source.setCredentialRef("env:INFLUX_CREDENTIAL");
        source.setEnabled("0");
        return source;
    }

    private Map<String, Object> testResult(boolean success, String status)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("success", success);
        result.put("status", status);
        result.put("category", success ? "SUCCESS" : "NETWORK");
        result.put("message", status);
        return result;
    }
}
