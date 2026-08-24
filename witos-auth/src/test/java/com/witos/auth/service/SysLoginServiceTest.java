package com.witos.auth.service;

import com.witos.auth.config.EmsMailProperties;
import com.witos.auth.config.EmsRegistrationProperties;
import com.witos.common.core.domain.R;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.message.mail.EmailUtil;
import com.witos.common.redis.service.RedisService;
import com.witos.system.api.RemoteUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysLoginServiceTest
{
    @Mock
    private RedisService redisService;

    @Mock
    private EmailUtil emailUtil;

    @Mock
    private RemoteUserService remoteUserService;

    @Mock
    private SysRecordLogService recordLogService;

    private EmsRegistrationProperties registrationProperties;

    private SysLoginService service;

    @BeforeEach
    void setUp()
    {
        registrationProperties = new EmsRegistrationProperties();
        registrationProperties.setInstallerEnabled(true);
        EmsMailProperties mailProperties = new EmsMailProperties(registrationProperties);
        mailProperties.setRegistrationSubject("注册验证码");

        service = new SysLoginService();
        ReflectionTestUtils.setField(service, "redisService", redisService);
        ReflectionTestUtils.setField(service, "emailUtil", emailUtil);
        ReflectionTestUtils.setField(service, "remoteUserService", remoteUserService);
        ReflectionTestUtils.setField(service, "recordLogService", recordLogService);
        ReflectionTestUtils.setField(service, "registrationProperties", registrationProperties);
        ReflectionTestUtils.setField(service, "mailProperties", mailProperties);
    }

    @Test
    void installerRegistrationUsesIndependentSwitch()
    {
        registrationProperties.setInstallerEnabled(false);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.sendRegisterEmailCode("user@example.com", "127.0.0.1", "device-1"));

        assertEquals("当前未开放安装商自助注册", exception.getMessage());
        verify(emailUtil, never()).sendSimpleMail(anyString(), anyString(), anyString());
    }

    @Test
    void ordinaryRegistrationDoesNotUseInstallerSwitch()
    {
        registrationProperties.setInstallerEnabled(false);
        when(remoteUserService.registerUserInfo(any(), anyString())).thenReturn(R.ok());

        service.register("normalUser", "12345");

        verify(remoteUserService).registerUserInfo(any(), anyString());
    }

    @Test
    void emailIpAndDeviceRateLimitsAreApplied()
    {
        when(redisService.setCacheObjectIfAbsent(anyString(), eq("1"), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(true, false);

        assertThrows(ServiceException.class,
                () -> service.sendRegisterEmailCode("user@example.com", "127.0.0.1", "device-1"));

        verify(emailUtil, never()).sendSimpleMail(anyString(), anyString(), anyString());
    }

    @Test
    void mailFailureDoesNotStoreCodeAndReleasesRateLimits()
    {
        when(redisService.setCacheObjectIfAbsent(anyString(), eq("1"), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        doThrow(new IllegalStateException("smtp down"))
                .when(emailUtil).sendSimpleMail(anyString(), anyString(), eq("user@example.com"));

        assertThrows(ServiceException.class,
                () -> service.sendRegisterEmailCode("user@example.com", "127.0.0.1", "device-1"));

        verify(redisService, never()).setCacheObject(anyString(), anyString(), anyLong(), eq(TimeUnit.MINUTES));
        verify(redisService).deleteObject(any(Collection.class));
    }

    @Test
    void validCodeIsConsumedAtomically()
    {
        when(redisService.getCacheObject("register_email_codes:user@example.com")).thenReturn("123456");
        when(redisService.compareAndDelete("register_email_codes:user@example.com", "123456")).thenReturn(true);

        service.validateRegisterEmailCode("USER@example.com", "123456");

        verify(redisService).compareAndDelete("register_email_codes:user@example.com", "123456");
    }

    @Test
    void expiredCodeIsRejected()
    {
        when(redisService.getCacheObject("register_email_codes:user@example.com")).thenReturn(null);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> service.validateRegisterEmailCode("user@example.com", "123456"));

        assertEquals("邮箱验证码已过期", exception.getMessage());
        verify(redisService, never()).compareAndDelete(anyString(), anyString());
    }
}