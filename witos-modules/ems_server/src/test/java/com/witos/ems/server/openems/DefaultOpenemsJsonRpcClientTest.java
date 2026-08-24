package com.witos.ems.server.openems;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.witos.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultOpenemsJsonRpcClientTest
{
    @Test
    void createEdgeReturnsOnlyRequiredCreationFields()
    {
        DefaultOpenemsJsonRpcClient client = new DefaultOpenemsJsonRpcClient()
        {
            @Override
            protected JSONObject call(Long serverEndpointId, String method, JSONObject params)
            {
                assertEquals("createEdge", method);
                assertEquals("EMS|request=req-1", params.getString("comment"));
                JSONObject result = new JSONObject();
                result.put("edgeId", "edge10001");
                result.put("apiKey", "api-key");
                result.put("setupPassword", "setup-password");
                return result;
            }
        };

        Map<String, Object> result = client.createEdge(1L, "EMS|request=req-1");

        assertEquals("edge10001", result.get("edgeId"));
        assertEquals("api-key", result.get("apiKey"));
        assertEquals("setup-password", result.get("setupPassword"));
        assertEquals(3, result.size());
    }

    @Test
    void incompleteAppSnapshotIsRejectedInsteadOfMarkingOldInstancesMissing()
    {
        DefaultOpenemsJsonRpcClient client = new DefaultOpenemsJsonRpcClient()
        {
            @Override
            protected JSONObject call(Long serverEndpointId, String method, JSONObject params)
            {
                JSONObject edgePayload = params.getJSONObject("payload");
                assertEquals("componentJsonApi", edgePayload.getString("method"));
                JSONObject componentParams = edgePayload.getJSONObject("params");
                assertEquals("_appManager", componentParams.getString("componentId"));
                String edgeMethod = componentParams.getJSONObject("payload").getString("method");
                JSONObject payloadResult = new JSONObject();
                if ("getApps".equals(edgeMethod))
                {
                    JSONObject app = new JSONObject();
                    app.put("appId", "App.PvSelfConsumption");
                    JSONArray instanceIds = new JSONArray();
                    instanceIds.add("11111111-1111-1111-1111-111111111111");
                    app.put("instanceIds", instanceIds);
                    JSONArray apps = new JSONArray();
                    apps.add(app);
                    payloadResult.put("apps", apps);
                }
                else
                {
                    payloadResult.put("instances", new JSONArray());
                }
                return wrapped(payloadResult);
            }
        };

        assertThrows(ServiceException.class, () -> client.getAppSnapshot(1L, "edge10001"));
    }

    @Test
    void adjacentBucketsReuseDailyResponseAndFilterByTimestamp()
    {
        StubClient client = new StubClient();
        List<String> channels = Collections.singletonList("meter0/ActivePower");

        Map<String, Object> first = client.queryHistoricTimeseries(1L, "edge0", channels,
                "2026-07-22 10:00:00", "2026-07-22 10:05:00");
        Map<String, Object> second = client.queryHistoricTimeseries(1L, "edge0", channels,
                "2026-07-22 10:05:00", "2026-07-22 10:10:00");
        Map<String, Object> third = client.queryHistoricTimeseries(1L, "edge0", channels,
            "2026-07-22 10:10:00", "2026-07-22 10:15:00");

        assertEquals(1, client.callCount);
        assertEquals(Collections.singletonList(10), first.get("meter0/ActivePower"));
        assertEquals(Collections.singletonList(20), second.get("meter0/ActivePower"));
        assertEquals(Collections.singletonList(30), third.get("meter0/ActivePower"));
    }

    @Test
    void bucketsOnDifferentDaysUseDifferentCacheKeysAndFilters()
    {
        StubClient client = new StubClient();
        List<String> channels = Collections.singletonList("meter0/ActivePower");

        Map<String, Object> firstDay = client.queryHistoricTimeseries(1L, "edge0", channels,
                "2026-07-22 10:00:00", "2026-07-22 10:05:00");
        Map<String, Object> secondDay = client.queryHistoricTimeseries(1L, "edge0", channels,
                "2026-07-23 10:00:00", "2026-07-23 10:05:00");

        assertEquals(2, client.callCount);
        assertEquals(Collections.singletonList(10), firstDay.get("meter0/ActivePower"));
        assertEquals(Collections.singletonList(40), secondDay.get("meter0/ActivePower"));
    }

    @Test
    void emptyDataOrTimestampsReturnNoValues()
    {
        EmptyPayloadClient emptyDataClient = new EmptyPayloadClient(true, false);
        EmptyPayloadClient emptyTimestampsClient = new EmptyPayloadClient(false, true);
        EmptyPayloadClient emptyArraysClient = new EmptyPayloadClient(false, false);
        List<String> channels = Collections.singletonList("meter0/ActivePower");

        Map<String, Object> emptyData = emptyDataClient.queryHistoricTimeseries(1L, "edge0", channels,
                "2026-07-22 10:00:00", "2026-07-22 10:05:00");
        Map<String, Object> emptyTimestamps = emptyTimestampsClient.queryHistoricTimeseries(1L, "edge0", channels,
                "2026-07-22 10:00:00", "2026-07-22 10:05:00");
        Map<String, Object> emptyArrays = emptyArraysClient.queryHistoricTimeseries(1L, "edge0", channels,
                "2026-07-22 10:00:00", "2026-07-22 10:05:00");

        assertTrue(emptyData.isEmpty());
        assertTrue(emptyTimestamps.isEmpty());
        assertEquals(Collections.emptyList(), emptyArrays.get("meter0/ActivePower"));
    }

    @Test
    void errorResponseIsNotCached()
    {
        ErrorThenSuccessClient client = new ErrorThenSuccessClient();
        List<String> channels = Collections.singletonList("meter0/ActivePower");

        Map<String, Object> failed = client.queryHistoricTimeseries(1L, "edge0", channels,
                "2026-07-22 10:00:00", "2026-07-22 10:05:00");
        Map<String, Object> retried = client.queryHistoricTimeseries(1L, "edge0", channels,
                "2026-07-22 10:00:00", "2026-07-22 10:05:00");

        assertTrue(failed.isEmpty());
        assertEquals(2, client.callCount);
        assertFalse(retried.isEmpty());
        assertEquals(Collections.singletonList(10), retried.get("meter0/ActivePower"));
    }

    private static class StubClient extends DefaultOpenemsJsonRpcClient
    {
        private int callCount;

        @Override
        protected JSONObject call(Long serverEndpointId, String method, JSONObject params)
        {
            callCount++;
            String fromDate = params.getJSONObject("payload").getJSONObject("params").getString("fromDate");
            JSONArray timestamps = new JSONArray();
            timestamps.add(fromDate + "T02:00:00Z");
            timestamps.add(fromDate + "T02:05:00Z");
            timestamps.add(fromDate + "T02:10:00Z");

            JSONArray values = new JSONArray();
            int offset = "2026-07-23".equals(fromDate) ? 30 : 0;
            values.add(10 + offset);
            values.add(20 + offset);
            values.add(30 + offset);

            JSONObject data = new JSONObject();
            data.put("meter0/ActivePower", values);
            JSONObject payloadResult = new JSONObject();
            payloadResult.put("timestamps", timestamps);
            payloadResult.put("data", data);
            JSONObject payload = new JSONObject();
            payload.put("result", payloadResult);
            JSONObject result = new JSONObject();
            result.put("payload", payload);
            return result;
        }
    }

    private static class EmptyPayloadClient extends DefaultOpenemsJsonRpcClient
    {
        private final boolean missingData;
        private final boolean missingTimestamps;

        private EmptyPayloadClient(boolean missingData, boolean missingTimestamps)
        {
            this.missingData = missingData;
            this.missingTimestamps = missingTimestamps;
        }

        @Override
        protected JSONObject call(Long serverEndpointId, String method, JSONObject params)
        {
            JSONObject payloadResult = new JSONObject();
            if (!missingData)
            {
                payloadResult.put("data", new JSONObject());
            }
            if (!missingTimestamps)
            {
                payloadResult.put("timestamps", new JSONArray());
            }
            return wrapped(payloadResult);
        }
    }

    private static class ErrorThenSuccessClient extends DefaultOpenemsJsonRpcClient
    {
        private int callCount;

        @Override
        protected JSONObject call(Long serverEndpointId, String method, JSONObject params)
        {
            callCount++;
            if (callCount == 1)
            {
                JSONObject payload = new JSONObject();
                payload.put("error", "timed out");
                JSONObject result = new JSONObject();
                result.put("payload", payload);
                return result;
            }
            JSONArray timestamps = new JSONArray();
            timestamps.add("2026-07-22T02:00:00Z");
            JSONArray values = new JSONArray();
            values.add(10);
            JSONObject data = new JSONObject();
            data.put("meter0/ActivePower", values);
            JSONObject payloadResult = new JSONObject();
            payloadResult.put("timestamps", timestamps);
            payloadResult.put("data", data);
            return wrapped(payloadResult);
        }
    }

    private static JSONObject wrapped(JSONObject payloadResult)
    {
        JSONObject payload = new JSONObject();
        payload.put("result", payloadResult);
        JSONObject result = new JSONObject();
        result.put("payload", payload);
        return result;
    }
}
