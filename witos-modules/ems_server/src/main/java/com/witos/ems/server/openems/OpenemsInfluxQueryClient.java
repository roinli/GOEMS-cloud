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
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenemsInfluxQueryClient
{
    private final OpenemsCredentialResolver credentialResolver;

    public OpenemsInfluxQueryClient(OpenemsCredentialResolver credentialResolver)
    {
        this.credentialResolver = credentialResolver;
    }

    public Sample queryLatest(EmsOpenemsEndpointSource source, String edgeKey, String field)
    {
        requireEnabled(source);
        if ("INFLUX_1".equals(source.getVersion()))
        {
            String query = "SELECT LAST(" + quoteIdentifier(field) + ") AS \"value\" FROM "
                    + qualifiedMeasurement(source, source.getMeasurement(), source.getRetentionPolicy())
                    + " WHERE " + quoteIdentifier(source.getEdgeTag()) + " = " + quoteString(edgeKey);
            return firstV1(executeV1(source, query));
        }
        if ("INFLUX_2".equals(source.getVersion()))
        {
            String flux = baseFlux(source, source.getMeasurement(), edgeKey, field, "1970-01-01T00:00:00Z", null)
                    + " |> last() |> keep(columns: [\"_time\", \"_value\", \"_field\"])";
            List<Sample> rows = parseV2(executeV2(source, flux));
            return rows.isEmpty() ? null : rows.get(rows.size() - 1);
        }
        throw new ServiceException("不支持的Influx版本：" + source.getVersion());
    }

    public List<Sample> queryHistory(EmsOpenemsEndpointSource source, String edgeKey, String field,
                                     String measurement, String retentionPolicy, Instant from, Instant to,
                                     int intervalSeconds, String aggregation, int timezoneOffsetSeconds)
    {
        requireEnabled(source);
        String function = normalizeAggregation(aggregation);
        if ("INFLUX_1".equals(source.getVersion()))
        {
            String query = "SELECT " + function + "(" + quoteIdentifier(field) + ") AS \"value\" FROM "
                    + qualifiedMeasurement(source, measurement, retentionPolicy)
                    + " WHERE " + quoteIdentifier(source.getEdgeTag()) + " = " + quoteString(edgeKey)
                    + " AND time >= " + from.getEpochSecond() + "s AND time < " + to.getEpochSecond() + "s"
                    + " GROUP BY time(" + intervalSeconds + "s," + (-timezoneOffsetSeconds) + "s) fill(none)";
            return parseV1(executeV1(source, query));
        }
        if ("INFLUX_2".equals(source.getVersion()))
        {
            String fluxFunction = function.toLowerCase();
            String flux = baseFlux(source, measurement, edgeKey, field, from.toString(), to.toString())
                    + " |> aggregateWindow(every: " + intervalSeconds + "s, fn: " + fluxFunction
                    + ", createEmpty: false, offset: " + (-timezoneOffsetSeconds) + "s)"
                    + " |> keep(columns: [\"_time\", \"_value\", \"_field\"])";
            return parseV2(executeV2(source, flux));
        }
        throw new ServiceException("不支持的Influx版本：" + source.getVersion());
    }

    private String executeV1(EmsOpenemsEndpointSource source, String query)
    {
        try
        {
            String database = StringUtils.isNotEmpty(source.getDatabaseName()) ? source.getDatabaseName() : source.getBucket();
            String url = trimTrailingSlash(source.getUrl()) + "/query?db="
                    + URLEncoder.encode(database, StandardCharsets.UTF_8.name()) + "&epoch=ms&q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
            return execute(source, "GET", url, null);
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new ServiceException("InfluxQL查询失败：" + message(ex));
        }
    }

    private String executeV2(EmsOpenemsEndpointSource source, String flux)
    {
        try
        {
            String url = trimTrailingSlash(source.getUrl()) + "/api/v2/query?org="
                    + URLEncoder.encode(source.getOrg(), StandardCharsets.UTF_8.name());
            return execute(source, "POST", url, flux);
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new ServiceException("Flux查询失败：" + message(ex));
        }
    }

    private String execute(EmsOpenemsEndpointSource source, String method, String url, String body) throws Exception
    {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try
        {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(secondsToMillis(source.getConnectTimeoutSeconds(), 10));
            connection.setReadTimeout(secondsToMillis(source.getReadTimeoutSeconds(), 60));
            connection.setRequestProperty("Accept", body == null ? "application/json" : "application/csv");
            String credential = credentialResolver.resolve(source.getCredentialRef());
            if (StringUtils.isNotEmpty(credential))
            {
                connection.setRequestProperty("Authorization", "INFLUX_2".equals(source.getVersion())
                        ? "Token " + credential : "Basic " + Base64.getEncoder()
                        .encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
            }
            if (body != null)
            {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/vnd.flux");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream())
                {
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = read(stream);
            if (status == 401 || status == 403)
            {
                throw new ServiceException("Influx认证失败，HTTP " + status);
            }
            if (status < 200 || status >= 300)
            {
                throw new ServiceException("Influx查询失败，HTTP " + status + suffix(response));
            }
            return response;
        }
        finally
        {
            connection.disconnect();
        }
    }

    private List<Sample> parseV1(String body)
    {
        JSONObject root = JSON.parseObject(body);
        if (root != null && StringUtils.isNotEmpty(root.getString("error")))
        {
            throw new ServiceException("Influx查询失败：" + root.getString("error"));
        }
        JSONArray results = root == null ? null : root.getJSONArray("results");
        JSONObject result = results == null || results.isEmpty() ? null : results.getJSONObject(0);
        if (result != null && StringUtils.isNotEmpty(result.getString("error")))
        {
            throw new ServiceException("Influx查询失败：" + result.getString("error"));
        }
        JSONArray series = result == null ? null : result.getJSONArray("series");
        if (series == null || series.isEmpty())
        {
            return Collections.emptyList();
        }
        JSONObject first = series.getJSONObject(0);
        JSONArray columns = first.getJSONArray("columns");
        JSONArray values = first.getJSONArray("values");
        int timeIndex = indexOf(columns, "time");
        int valueIndex = indexOf(columns, "value");
        List<Sample> rows = new ArrayList<Sample>();
        if (values == null || timeIndex < 0 || valueIndex < 0)
        {
            return rows;
        }
        for (Object item : values)
        {
            JSONArray row = (JSONArray) item;
            Object value = row.get(valueIndex);
            if (value == null)
            {
                continue;
            }
            rows.add(new Sample(parseTime(row.get(timeIndex)), value));
        }
        return rows;
    }

    private Sample firstV1(String body)
    {
        List<Sample> rows = parseV1(body);
        return rows.isEmpty() ? null : rows.get(rows.size() - 1);
    }

    private List<Sample> parseV2(String body)
    {
        List<Sample> rows = new ArrayList<Sample>();
        List<String> headers = null;
        for (String line : body.split("\\r?\\n"))
        {
            if (StringUtils.isEmpty(line) || line.startsWith("#"))
            {
                continue;
            }
            List<String> cells = parseCsvRow(line);
            if (cells.contains("_time") && cells.contains("_value"))
            {
                headers = cells;
                continue;
            }
            if (headers == null)
            {
                continue;
            }
            int timeIndex = headers.indexOf("_time");
            int valueIndex = headers.indexOf("_value");
            if (timeIndex >= cells.size() || valueIndex >= cells.size() || StringUtils.isEmpty(cells.get(valueIndex)))
            {
                continue;
            }
            rows.add(new Sample(Date.from(Instant.parse(cells.get(timeIndex))), parseValue(cells.get(valueIndex))));
        }
        rows.sort((left, right) -> left.getSampleTime().compareTo(right.getSampleTime()));
        return rows;
    }

    private List<String> parseCsvRow(String line)
    {
        List<String> cells = new ArrayList<String>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++)
        {
            char current = line.charAt(i);
            if (current == '"')
            {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"')
                {
                    value.append('"');
                    i++;
                }
                else
                {
                    quoted = !quoted;
                }
            }
            else if (current == ',' && !quoted)
            {
                cells.add(value.toString());
                value.setLength(0);
            }
            else
            {
                value.append(current);
            }
        }
        cells.add(value.toString());
        return cells;
    }

    private String baseFlux(EmsOpenemsEndpointSource source, String measurement, String edgeKey, String field,
                            String from, String to)
    {
        return "from(bucket: " + quoteFlux(source.getBucket()) + ")"
                + " |> range(start: " + from + (to == null ? ")" : ", stop: " + to + ")")
                + " |> filter(fn: (r) => r._measurement == " + quoteFlux(measurement) + ")"
                + " |> filter(fn: (r) => r[" + quoteFlux(source.getEdgeTag()) + "] == " + quoteFlux(edgeKey) + ")"
                + " |> filter(fn: (r) => r._field == " + quoteFlux(field) + ")";
    }

    private String normalizeAggregation(String value)
    {
        String normalized = StringUtils.isEmpty(value) ? "MEAN" : value.toUpperCase();
        if (!"MEAN".equals(normalized) && !"LAST".equals(normalized) && !"FIRST".equals(normalized)
                && !"MIN".equals(normalized) && !"MAX".equals(normalized) && !"SUM".equals(normalized)
                && !"COUNT".equals(normalized))
        {
            throw new ServiceException("聚合方式仅支持MEAN/LAST/FIRST/MIN/MAX/SUM/COUNT");
        }
        return normalized;
    }

    private Object parseValue(String value)
    {
        if (value.matches("[-+]?\\d+"))
        {
            try
            {
                return Long.valueOf(value);
            }
            catch (NumberFormatException ignored)
            {
                return new BigDecimal(value);
            }
        }
        if (value.matches("[-+]?(?:\\d+\\.\\d*|\\d*\\.\\d+)(?:[eE][-+]?\\d+)?"))
        {
            return new BigDecimal(value);
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))
        {
            return Boolean.valueOf(value);
        }
        return value;
    }

    private Date parseTime(Object value)
    {
        if (value instanceof Number)
        {
            return new Date(((Number) value).longValue());
        }
        String text = String.valueOf(value);
        if (text.matches("\\d+"))
        {
            return new Date(Long.parseLong(text));
        }
        return Date.from(Instant.parse(text));
    }

    private int indexOf(JSONArray columns, String value)
    {
        if (columns == null)
        {
            return -1;
        }
        for (int i = 0; i < columns.size(); i++)
        {
            if (value.equals(columns.getString(i)))
            {
                return i;
            }
        }
        return -1;
    }

    private String qualifiedMeasurement(EmsOpenemsEndpointSource source, String measurement, String retentionPolicy)
    {
        String quotedMeasurement = quoteIdentifier(measurement);
        return StringUtils.isEmpty(retentionPolicy)
                ? quotedMeasurement : quoteIdentifier(retentionPolicy) + "." + quotedMeasurement;
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

    private void requireEnabled(EmsOpenemsEndpointSource source)
    {
        if (source == null || !"0".equals(source.getEnabled()))
        {
            throw new ServiceException("Influx数据源未配置或未启用");
        }
    }

    private int secondsToMillis(Integer seconds, int defaultSeconds)
    {
        return Math.multiplyExact(seconds == null ? defaultSeconds : seconds, 1000);
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

    private String suffix(String body)
    {
        if (StringUtils.isEmpty(body))
        {
            return "";
        }
        String compact = body.replace('\r', ' ').replace('\n', ' ').trim();
        return "：" + (compact.length() > 256 ? compact.substring(0, 256) : compact);
    }

    private String message(Exception ex)
    {
        return StringUtils.isEmpty(ex.getMessage()) ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    public static class Sample
    {
        private final Date sampleTime;
        private final Object value;

        public Sample(Date sampleTime, Object value)
        {
            this.sampleTime = sampleTime;
            this.value = value;
        }

        public Date getSampleTime()
        {
            return sampleTime;
        }

        public Object getValue()
        {
            return value;
        }
    }
}
