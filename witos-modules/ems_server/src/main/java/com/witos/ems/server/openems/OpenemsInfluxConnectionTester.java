package com.witos.ems.server.openems;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.witos.common.core.exception.ServiceException;
import com.witos.common.core.utils.StringUtils;
import com.witos.ems.server.domain.entity.EmsOpenemsEndpointSource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OpenemsInfluxConnectionTester
{
    private final OpenemsCredentialResolver credentialResolver;

    public OpenemsInfluxConnectionTester(OpenemsCredentialResolver credentialResolver)
    {
        this.credentialResolver = credentialResolver;
    }

    public Map<String, Object> test(EmsOpenemsEndpointSource source)
    {
        long startedAt = System.currentTimeMillis();
        if (source == null || !"0".equals(source.getEnabled()))
        {
            return result(source, false, "NOT_CONFIGURED", null, "数据源未配置或未启用", startedAt, false, null);
        }
        try
        {
            String credential = credentialResolver.resolve(source.getCredentialRef());
            if ("INFLUX_1".equals(source.getVersion()))
            {
                return testInfluxV1(source, credential, startedAt);
            }
            if ("INFLUX_2".equals(source.getVersion()))
            {
                return testInfluxV2(source, credential, startedAt);
            }
            return result(source, false, "FAILED", "UNSUPPORTED_VERSION", "不支持的Influx版本", startedAt, false, null);
        }
        catch (Exception ex)
        {
            String message = ex.getMessage() == null ? "Influx连接测试失败" : ex.getMessage();
            return result(source, false, "FAILED", classify(message), message, startedAt, false, null);
        }
    }

    private Map<String, Object> testInfluxV1(EmsOpenemsEndpointSource source, String credential, long startedAt) throws Exception
    {
        String database = StringUtils.isNotEmpty(source.getDatabaseName()) ? source.getDatabaseName() : source.getBucket();
        String measurement = qualifiedMeasurement(source);
        String tagQuery = "SHOW TAG VALUES FROM " + measurement + " WITH KEY = " + quoteIdentifier(source.getEdgeTag()) + " LIMIT 1";
        HttpResult tagResult = execute(source, "GET", v1QueryUrl(source, database, tagQuery), credential, null, null);
        ensureSuccess(tagResult);
        ensureInfluxV1QuerySuccess(tagResult.body);
        String edgeId = firstInfluxV1TagValue(tagResult.body);
        if (StringUtils.isNotEmpty(edgeId))
        {
            String sampleQuery = "SELECT * FROM " + measurement + " WHERE " + quoteIdentifier(source.getEdgeTag())
                    + " = " + quoteString(edgeId) + " ORDER BY time DESC LIMIT 1";
            HttpResult sampleResult = execute(source, "GET", v1QueryUrl(source, database, sampleQuery), credential, null, null);
            ensureSuccess(sampleResult);
            ensureInfluxV1QuerySuccess(sampleResult.body);
        }
        String message = StringUtils.isEmpty(edgeId)
                ? "InfluxDB 1.x连接和查询成功，当前measurement未发现Edge数据"
                : "InfluxDB 1.x连接、Edge标签和最小数据查询成功";
        return result(source, true, "SUCCESS", null, message, startedAt, StringUtils.isNotEmpty(edgeId), edgeId);
    }

    private Map<String, Object> testInfluxV2(EmsOpenemsEndpointSource source, String credential, long startedAt) throws Exception
    {
        String flux = "from(bucket: " + quoteFlux(source.getBucket()) + ")"
                + " |> range(start: -1h)"
                + " |> filter(fn: (r) => r._measurement == " + quoteFlux(source.getMeasurement()) + ")"
                + " |> keep(columns: [\"_time\", \"_field\", \"_value\", " + quoteFlux(source.getEdgeTag()) + "])"
                + " |> limit(n: 1)";
        String url = trimTrailingSlash(source.getUrl()) + "/api/v2/query?org="
                + URLEncoder.encode(source.getOrg(), StandardCharsets.UTF_8.name());
        HttpResult response = execute(source, "POST", url, credential, "application/vnd.flux", flux);
        ensureSuccess(response);
        boolean dataAvailable = hasInfluxV2Data(response.body);
        String message = dataAvailable
                ? "InfluxDB 2.x连接、measurement和Edge标签查询成功"
                : "InfluxDB 2.x连接和查询成功，最近1小时未发现数据";
        return result(source, true, "SUCCESS", null, message, startedAt, dataAvailable, null);
    }

    private HttpResult execute(EmsOpenemsEndpointSource source, String method, String url, String credential,
                               String contentType, String body) throws Exception
    {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(secondsToMillis(source.getConnectTimeoutSeconds(), 10));
        connection.setReadTimeout(secondsToMillis(source.getReadTimeoutSeconds(), 60));
        connection.setRequestProperty("Accept", "application/json, application/csv");
        if (StringUtils.isNotEmpty(credential))
        {
            if ("INFLUX_2".equals(source.getVersion()))
            {
                connection.setRequestProperty("Authorization", "Token " + credential);
            }
            else
            {
                connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
            }
        }
        if (body != null)
        {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream())
            {
                output.write(bytes);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        return new HttpResult(status, read(stream));
    }

    private void ensureSuccess(HttpResult result)
    {
        if (result.status >= 200 && result.status < 300)
        {
            return;
        }
        if (result.status == 401 || result.status == 403)
        {
            throw new ServiceException("Influx认证失败，HTTP " + result.status);
        }
        throw new ServiceException("Influx查询失败，HTTP " + result.status + errorSuffix(result.body));
    }

    private String firstInfluxV1TagValue(String body)
    {
        JSONObject root = JSON.parseObject(body);
        JSONArray results = root == null ? null : root.getJSONArray("results");
        JSONObject firstResult = results == null || results.isEmpty() ? null : results.getJSONObject(0);
        JSONArray series = firstResult == null ? null : firstResult.getJSONArray("series");
        JSONObject firstSeries = series == null || series.isEmpty() ? null : series.getJSONObject(0);
        JSONArray values = firstSeries == null ? null : firstSeries.getJSONArray("values");
        JSONArray firstValue = values == null || values.isEmpty() ? null : values.getJSONArray(0);
        return firstValue == null || firstValue.size() < 2 ? null : firstValue.getString(1);
    }

    private void ensureInfluxV1QuerySuccess(String body)
    {
        JSONObject root = JSON.parseObject(body);
        if (root != null && StringUtils.isNotEmpty(root.getString("error")))
        {
            throw new ServiceException("Influx查询失败：" + root.getString("error"));
        }
        JSONArray results = root == null ? null : root.getJSONArray("results");
        JSONObject firstResult = results == null || results.isEmpty() ? null : results.getJSONObject(0);
        if (firstResult != null && StringUtils.isNotEmpty(firstResult.getString("error")))
        {
            throw new ServiceException("Influx查询失败：" + firstResult.getString("error"));
        }
    }

    private boolean hasInfluxV2Data(String body)
    {
        if (StringUtils.isEmpty(body))
        {
            return false;
        }
        String[] lines = body.split("\\r?\\n");
        for (String line : lines)
        {
            if (StringUtils.isNotEmpty(line) && !line.startsWith("#") && !line.startsWith(",result,table"))
            {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> result(EmsOpenemsEndpointSource source, boolean success, String status, String errorCode,
                                       String message, long startedAt, boolean dataAvailable, String sampleEdgeId)
    {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sourceType", source == null ? null : source.getSourceType());
        result.put("success", success);
        result.put("status", status);
        result.put("version", source == null ? null : source.getVersion());
        result.put("latencyMs", Math.max(0L, System.currentTimeMillis() - startedAt));
        result.put("dataAvailable", dataAvailable);
        result.put("sampleEdgeId", sampleEdgeId);
        result.put("errorCode", errorCode);
        result.put("message", message);
        return result;
    }

    private String v1QueryUrl(EmsOpenemsEndpointSource source, String database, String query) throws Exception
    {
        return trimTrailingSlash(source.getUrl()) + "/query?db=" + URLEncoder.encode(database, StandardCharsets.UTF_8.name())
                + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
    }

    private String qualifiedMeasurement(EmsOpenemsEndpointSource source)
    {
        String measurement = quoteIdentifier(source.getMeasurement());
        return StringUtils.isEmpty(source.getRetentionPolicy())
                ? measurement : quoteIdentifier(source.getRetentionPolicy()) + "." + measurement;
    }

    private String quoteIdentifier(String value)
    {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String quoteString(String value)
    {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private String quoteFlux(String value)
    {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String trimTrailingSlash(String value)
    {
        String result = value;
        while (result.endsWith("/"))
        {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private int secondsToMillis(Integer seconds, int defaultSeconds)
    {
        int value = seconds == null ? defaultSeconds : seconds;
        return Math.multiplyExact(value, 1000);
    }

    private String read(InputStream stream) throws Exception
    {
        if (stream == null)
        {
            return "";
        }
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                content.append(line).append('\n');
            }
        }
        return content.toString();
    }

    private String classify(String message)
    {
        String lower = message.toLowerCase();
        if (lower.contains("认证") || lower.contains("credential") || lower.contains("unauthorized") || lower.contains("forbidden"))
        {
            return "AUTH";
        }
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("超时"))
        {
            return "TIMEOUT";
        }
        if (lower.contains("query") || lower.contains("查询"))
        {
            return "QUERY";
        }
        return "NETWORK";
    }

    private String errorSuffix(String body)
    {
        if (StringUtils.isEmpty(body))
        {
            return "";
        }
        String compact = body.replace('\r', ' ').replace('\n', ' ').trim();
        return compact.length() > 256 ? "：" + compact.substring(0, 256) : "：" + compact;
    }

    private static class HttpResult
    {
        private final int status;
        private final String body;

        private HttpResult(int status, String body)
        {
            this.status = status;
            this.body = body;
        }
    }
}
