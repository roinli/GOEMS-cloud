package com.witos.ems.server.openems;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmsOpenemsHistorySyncServiceTest
{
    @Test
    void deltaUsesLastValueMinusFirstValue()
    {
        EmsOpenemsHistorySyncService.AggregatedMetric metric = EmsOpenemsHistorySyncService.aggregate(
                Arrays.asList(new BigDecimal("100.25"), new BigDecimal("104.75")), "DELTA");

        assertEquals(0, new BigDecimal("4.50").compareTo(metric.value));
        assertEquals("GOOD", metric.quality);
    }

    @Test
    void deltaWithEqualValuesIsGoodAndZero()
    {
        EmsOpenemsHistorySyncService.AggregatedMetric metric = EmsOpenemsHistorySyncService.aggregate(
                Arrays.asList(new BigDecimal("100"), new BigDecimal("100")), "DELTA");

        assertEquals(0, BigDecimal.ZERO.compareTo(metric.value));
        assertEquals("GOOD", metric.quality);
    }

    @Test
    void deltaRollbackIsBadAndDoesNotProduceNegativeConsumption()
    {
        EmsOpenemsHistorySyncService.AggregatedMetric metric = EmsOpenemsHistorySyncService.aggregate(
                Arrays.asList(new BigDecimal("999"), new BigDecimal("3")), "DELTA");

        assertEquals(0, BigDecimal.ZERO.compareTo(metric.value));
        assertEquals("BAD", metric.quality);
        assertTrue(metric.qualityReason.contains("回退"));
    }
}