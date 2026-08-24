package com.witos.ems.server.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmsMonitorServiceImplTest
{
    @Test
    void historyTimeUsesBusinessTimezoneWhenJvmRunsInUtc()
    {
        TimeZone previous = TimeZone.getDefault();
        try
        {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            EmsMonitorServiceImpl service = new EmsMonitorServiceImpl();

            Date parsed = ReflectionTestUtils.invokeMethod(
                    service, "historyTime", "2026-08-16 15:16:14", new Date(0));

            assertEquals(Instant.parse("2026-08-16T07:16:14Z"), parsed.toInstant());
        }
        finally
        {
            TimeZone.setDefault(previous);
        }
    }
}
