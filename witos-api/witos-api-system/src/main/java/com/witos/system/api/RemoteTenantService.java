package com.witos.system.api;

import com.witos.common.core.constant.SecurityConstants;
import com.witos.common.core.constant.ServiceNameConstants;
import com.witos.common.core.domain.R;
import com.witos.system.api.domain.SysTenantRegister;
import com.witos.system.api.factory.RemoteTenantFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(contextId = "remoteTenantService", value = ServiceNameConstants.SYSTEM_SERVICE, fallbackFactory = RemoteTenantFallbackFactory.class)
public interface RemoteTenantService
{
    @PostMapping("/tenant/register-installer")
    R<Object> registerInstaller(@RequestBody SysTenantRegister body, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}