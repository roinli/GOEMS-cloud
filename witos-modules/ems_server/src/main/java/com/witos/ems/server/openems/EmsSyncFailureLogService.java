package com.witos.ems.server.openems;

import com.witos.ems.server.domain.entity.EmsDeviceComponent;
import com.witos.ems.server.domain.entity.EmsSyncLog;
import com.witos.ems.server.mapper.EmsSyncLogMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

@Service
public class EmsSyncFailureLogService
{
    @Resource
    private EmsSyncLogMapper syncLogMapper;

    public void record(EmsDeviceComponent component, String syncType, String bizKey, String errorMessage)
    {
        Date now = new Date();
        String normalizedMessage = errorMessage == null ? "未知同步错误" : errorMessage;
        String errorHash = sha256(normalizedMessage);
        EmsSyncLog log = new EmsSyncLog();
        log.setTenantId(component.getTenantId());
        log.setCompanyId(component.getCompanyId());
        log.setStationId(component.getStationId());
        log.setSyncType(syncType);
        log.setBizKey(bizKey);
        log.setIssueKey(component.getId() + ":" + errorHash.substring(0, 32));
        log.setStatus("FAILED");
        log.setStartedAt(now);
        log.setFinishedAt(now);
        log.setErrorMessage(normalizedMessage);
        log.setErrorHash(errorHash);
        log.setOccurrenceCount(1L);
        log.setFirstOccurredAt(now);
        log.setLastOccurredAt(now);
        log.setCreateTime(now);
        log.setUpdateTime(now);
        syncLogMapper.upsertFailure(log);
    }

    private String sha256(String value)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest)
            {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        }
        catch (NoSuchAlgorithmException ex)
        {
            throw new IllegalStateException("当前JVM不支持SHA-256", ex);
        }
    }
}
