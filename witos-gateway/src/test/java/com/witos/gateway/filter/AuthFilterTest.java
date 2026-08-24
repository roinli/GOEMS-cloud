package com.witos.gateway.filter;

import com.witos.common.core.constant.SecurityConstants;
import com.witos.common.core.constant.TokenConstants;
import com.witos.common.core.utils.JwtUtils;
import com.witos.common.redis.service.RedisService;
import com.witos.gateway.config.properties.IgnoreWhiteProperties;
import org.junit.Before;
import org.junit.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AuthFilterTest
{
    private AuthFilter filter;
    private RedisService redisService;

    @Before
    public void setUp()
    {
        filter = new AuthFilter();
        redisService = mock(RedisService.class);
        IgnoreWhiteProperties ignoreWhite = new IgnoreWhiteProperties();
        ReflectionTestUtils.setField(filter, "redisService", redisService);
        ReflectionTestUtils.setField(filter, "ignoreWhite", ignoreWhite);
    }

    @Test
    public void forwardsTenantIdFromJwtToDownstreamService()
    {
        Map<String, Object> claims = new HashMap<String, Object>();
        claims.put(SecurityConstants.USER_KEY, "session-key");
        claims.put(SecurityConstants.DETAILS_USER_ID, 105L);
        claims.put(SecurityConstants.DETAILS_USERNAME, "1005");
        claims.put(SecurityConstants.DETAILS_DEPT_ID, 111L);
        claims.put(SecurityConstants.DETAILS_TENANT_ID, 1005L);
        String token = JwtUtils.createToken(claims);
        when(redisService.hasKey(anyString())).thenReturn(true);

        MockServerHttpRequest request = MockServerHttpRequest.get("/ems/device/list")
                .header(HttpHeaders.AUTHORIZATION, TokenConstants.PREFIX + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        final ServerWebExchange[] forwarded = new ServerWebExchange[1];
        GatewayFilterChain chain = current -> {
            forwarded[0] = current;
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertEquals("1005", forwarded[0].getRequest().getHeaders()
                .getFirst(SecurityConstants.DETAILS_TENANT_ID));
    }

}
