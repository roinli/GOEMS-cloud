package com.witos.ems.server.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmsViewMapperSqlTest
{
    @Test
    void homeSummaryQueriesUseStationTenantInsteadOfCurrentLoginTenant() throws Exception
    {
        for (String methodName : Arrays.asList(
                "selectStationRevenueSummary",
                "selectStationRevenueQuality",
                "selectStationHomeMetricSummary",
                "selectStationDailyTrend",
                "selectStationSyncStatus"))
        {
            Method method = EmsViewMapper.class.getMethod(methodName, List.class);
            InterceptorIgnore annotation = method.getAnnotation(InterceptorIgnore.class);

            assertNotNull(annotation, methodName);
            assertEquals("1", annotation.tenantLine(), methodName);
        }
    }

    @Test
    void homeStorageEnergyUsesDailyDeltasInsteadOfRealtimeLifetimeSnapshot()
    {
        String sql = mapperXml("/mapper/ems/EmsViewMapper.xml");

        assertTrue(sql.contains("ifnull(today.today_charge_kwh, 0) as todayChargeKwh"));
        assertTrue(sql.contains("ifnull(hist.total_charge_kwh, 0) + ifnull(today.today_charge_kwh, 0) as totalChargeKwh"));
        assertTrue(sql.contains("h.quality = 'PARTIAL'"));
        assertFalse(sql.contains("snapshot_storage_energy"));
        assertFalse(sql.contains("greatest(ifnull(today.today_charge_kwh"));
    }

    @Test
    void homeCumulativeEnergyFallsBackToHourlyReportsForMissingDailyRows()
    {
        String sql = mapperXml("/mapper/ems/EmsViewMapper.xml");

        assertTrue(sql.contains("historic_day_energy as"));
        assertTrue(sql.contains("historic_hour_energy as"));
        assertTrue(sql.contains("from ems_report_station_hour r"));
        assertTrue(sql.contains("not exists ("));
        assertTrue(sql.contains("from ems_report_station_day daily"));
        assertTrue(sql.contains("union all"));
    }

    @Test
    void reportAggregationKeepsValidPartialEnergyDeltas()
    {
        String sql = mapperXml("/mapper/ems/EmsReportSyncTaskMapper.xml");

        assertTrue(sql.contains("max(case when quality in ('GOOD', 'PARTIAL') then metric_value else null end)"));
        assertTrue(sql.contains("and h.metric_key in ('charge', 'chargeKwh', 'charge_kwh', 'chargeEnergyKwh')"));
        assertTrue(sql.contains("and h.metric_key in ('discharge', 'dischargeKwh', 'discharge_kwh', 'dischargeEnergyKwh')"));
        assertTrue(occurrences(sql, "when h.quality in ('GOOD', 'PARTIAL')") >= 3);
    }

    @Test
    void deviceHistoryDoesNotHardCodeLoginTenant()
    {
        String sql = mapperXml("/mapper/ems/EmsViewMapper.xml");

        assertTrue(sql.contains("id=\"selectDeviceMetricHistory\""));
        assertFalse(sql.contains("where h.tenant_id = #{tenantId}"));
    }

    @Test
    void deviceHistoryUsesPreAuthorizedDeviceIdsWithoutTenantInterceptor() throws Exception
    {
        Method method = EmsViewMapper.class.getMethod(
                "selectDeviceMetricHistory", List.class, String.class, java.util.Date.class, java.util.Date.class);
        InterceptorIgnore annotation = method.getAnnotation(InterceptorIgnore.class);

        assertNotNull(annotation);
        assertEquals("1", annotation.tenantLine());
    }

    private String mapperXml(String resourcePath)
    {
        InputStream stream = EmsViewMapperSqlTest.class.getResourceAsStream(resourcePath);
        assertTrue(stream != null, resourcePath + " must be available on the test classpath");
        try (Scanner scanner = new Scanner(stream, "UTF-8").useDelimiter("\\A"))
        {
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    private int occurrences(String text, String value)
    {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(value, index)) >= 0)
        {
            count++;
            index += value.length();
        }
        return count;
    }
}
