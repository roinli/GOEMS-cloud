package com.witos.system.api.factory;

import com.witos.system.api.RemoteUserService;
import com.witos.system.api.domain.EmsUserRolePayload;
import com.witos.system.api.domain.SysRole;
import com.witos.system.api.domain.SysUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;
import com.witos.common.core.domain.R;
import com.witos.system.api.model.LoginUser;

import java.util.List;

/**
 * 用户服务降级处理
 *
 * @author witos
 */
@Component
public class RemoteUserFallbackFactory implements FallbackFactory<RemoteUserService>
{
    private static final Logger log = LoggerFactory.getLogger(RemoteUserFallbackFactory.class);

    @Override
    public RemoteUserService create(Throwable throwable)
    {
        log.error("用户服务调用失败:{}", throwable.getMessage());
        return new RemoteUserService()
        {
            @Override
            public R<LoginUser> getUserInfo(String username, String source)
            {
                return R.fail("获取用户失败:" + throwable.getMessage());
            }

            @Override
            public R<Boolean> registerUserInfo(SysUser sysUser, String source)
            {
                return R.fail("注册用户失败:" + throwable.getMessage());
            }

            @Override
            public R<SysUser> getEmsUser(Long userId, String source)
            {
                return R.fail("获取EMS用户失败:" + throwable.getMessage());
            }

            @Override
            public R<Boolean> checkEmsUserNameUnique(SysUser sysUser, String source)
            {
                return R.fail("校验EMS用户名失败:" + throwable.getMessage());
            }

            @Override
            public R<Boolean> checkEmsPhoneUnique(SysUser sysUser, String source)
            {
                return R.fail("校验EMS手机号失败:" + throwable.getMessage());
            }

            @Override
            public R<Boolean> checkEmsEmailUnique(SysUser sysUser, String source)
            {
                return R.fail("校验EMS邮箱失败:" + throwable.getMessage());
            }

            @Override
            public R<SysUser> addEmsUser(SysUser sysUser, String source)
            {
                return R.fail("新增EMS用户失败:" + throwable.getMessage());
            }

            @Override
            public R<Boolean> updateEmsUser(SysUser sysUser, String source)
            {
                return R.fail("更新EMS用户失败:" + throwable.getMessage());
            }

            @Override
            public R<Boolean> deleteEmsUser(Long userId, String source)
            {
                return R.fail("删除EMS用户失败:" + throwable.getMessage());
            }

            @Override
            public R<Long> getEmsDefaultDeptId(Long currentUserId, String source)
            {
                return R.fail("获取EMS默认部门失败:" + throwable.getMessage());
            }

            @Override
            public R<List<SysRole>> listEmsRoles(Long tenantId, String source)
            {
                return R.fail("获取EMS角色失败:" + throwable.getMessage());
            }

            @Override
            public R<List<SysRole>> listEmsUserRoles(Long userId, Long tenantId, String source)
            {
                return R.fail("获取EMS用户角色失败:" + throwable.getMessage());
            }

            @Override
            public R<Boolean> replaceEmsUserRoles(EmsUserRolePayload payload, String source)
            {
                return R.fail("保存EMS用户角色失败:" + throwable.getMessage());
            }
        };
    }
}
