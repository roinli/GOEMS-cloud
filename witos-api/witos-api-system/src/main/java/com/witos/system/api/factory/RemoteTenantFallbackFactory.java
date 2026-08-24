package com.witos.system.api.factory;

import com.witos.common.core.domain.R;
import com.witos.system.api.RemoteTenantService;
import com.witos.system.api.domain.SysTenantRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class RemoteTenantFallbackFactory implements FallbackFactory<RemoteTenantService>
{
    private static final Logger log = LoggerFactory.getLogger(RemoteTenantFallbackFactory.class);

    @Override
    public RemoteTenantService create(Throwable throwable)
    {
        log.error("租户服务调用失败:{}", throwable.getMessage());
        return new RemoteTenantService()
        {
            @Override
            public R<Object> registerInstaller(SysTenantRegister body, String source)
            {
                return R.fail("注册安装商失败:" + throwable.getMessage());
            }
        };
    }
}