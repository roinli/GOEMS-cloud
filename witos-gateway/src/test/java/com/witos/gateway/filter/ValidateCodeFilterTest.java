package com.witos.gateway.filter;

import com.witos.gateway.config.properties.CaptchaProperties;
import com.witos.gateway.service.ValidateCodeService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

public class ValidateCodeFilterTest
{
    private ValidateCodeFilter filter;
    private ValidateCodeService validateCodeService;

    @Before
    public void setUp()
    {
        filter = new ValidateCodeFilter();
        validateCodeService = mock(ValidateCodeService.class);
        CaptchaProperties captchaProperties = new CaptchaProperties();
        captchaProperties.setEnabled(true);
        ReflectionTestUtils.setField(filter, "validateCodeService", validateCodeService);
        ReflectionTestUtils.setField(filter, "captchaProperties", captchaProperties);
    }

    @Test
    public void loginDoesNotRequireCaptcha()
    {
        MockServerHttpRequest request = MockServerHttpRequest.post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"username\":\"admin\",\"password\":\"password\"}");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        final boolean[] forwarded = new boolean[1];
        GatewayFilterChain chain = current -> {
            forwarded[0] = true;
            return Mono.empty();
        };
        GatewayFilter gatewayFilter = filter.apply(new Object());

        gatewayFilter.filter(exchange, chain).block();

        assertTrue(forwarded[0]);
        verifyNoInteractions(validateCodeService);
    }
}
