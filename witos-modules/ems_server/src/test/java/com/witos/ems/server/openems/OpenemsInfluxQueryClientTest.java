package com.witos.ems.server.openems;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.witos.ems.server.domain.entity.EmsOpenemsEndpointSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenemsInfluxQueryClientTest
{
    private HttpServer server;

    @BeforeEach
    void setUp()
    {
        System.setProperty("openems.test.influx.query.credential", "user:password");
    }

    @AfterEach
    void tearDown()
    {
        System.clearProperty("openems.test.influx.query.credential");
        if (server != null)
        {
            server.stop(0);
        }
    }

    @Test
    void influxQlLatestUsesNumericEdgeTagAndReturnsSampleTime() throws Exception
    {
        AtomicReference<String> query = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/query", exchange -> {
            query.set(queryParam(exchange.getRequestURI().getRawQuery(), "q"));
            respond(exchange, 200, "{\"results\":[{\"series\":[{\"columns\":[\"time\",\"value\"],"
                    + "\"values\":[[1786060800000,123.5]]}]}]}");
        });
        server.start();
        OpenemsInfluxQueryClient.Sample sample = client().queryLatest(source("INFLUX_1"), "7", "meter0/ActivePower");

        assertEquals(123.5, ((Number) sample.getValue()).doubleValue());
        assertEquals(1786060800000L, sample.getSampleTime().getTime());
        assertTrue(query.get().contains("LAST(\"meter0/ActivePower\")"));
        assertTrue(query.get().contains("\"edge\" = '7'"));
    }

    @Test
    void influxQlNullValueStaysMissingInsteadOfZero() throws Exception
    {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/query", exchange -> respond(exchange, 200,
                "{\"results\":[{\"series\":[{\"columns\":[\"time\",\"value\"],\"values\":[[1786060800000,null]]}]}]}"));
        server.start();

        assertNull(client().queryLatest(source("INFLUX_1"), "7", "meter0/ActivePower"));
    }

    @Test
    void influxQlHistoryQualifiesRetentionPolicyAndUsesLeftClosedRightOpenRange() throws Exception
    {
        AtomicReference<String> query = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/query", exchange -> {
            query.set(queryParam(exchange.getRequestURI().getRawQuery(), "q"));
            respond(exchange, 200, "{\"results\":[{}]}");
        });
        server.start();

        client().queryHistory(source("INFLUX_1"), "7", "meter0/ActivePower", "avg", "rp_avg",
                Instant.parse("2026-08-07T00:00:00Z"), Instant.parse("2026-08-07T00:10:00Z"), 300, "MEAN", 28800);

        assertTrue(query.get().contains("FROM \"rp_avg\".\"avg\""));
        assertTrue(query.get().contains("time >= 1786060800s AND time < 1786061400s"));
        assertTrue(query.get().contains("GROUP BY time(300s,-28800s)"));
    }

    @Test
    void fluxHistoryUsesConfiguredMeasurementAndParsesAnnotatedCsv() throws Exception
    {
        AtomicReference<String> flux = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v2/query", exchange -> {
            flux.set(read(exchange.getRequestBody()));
            respond(exchange, 200, "#datatype,string,long,dateTime:RFC3339,double,string\n"
                    + ",result,table,_time,_value,_field\n"
                    + ",_result,0,2026-08-07T00:00:00Z,12.25,meter0/ActivePower\n"
                    + ",_result,0,2026-08-07T00:05:00Z,13.5,meter0/ActivePower\n");
        });
        server.start();
        EmsOpenemsEndpointSource source = source("INFLUX_2");
        source.setOrg("openems");
        source.setBucket("openems");

        List<OpenemsInfluxQueryClient.Sample> rows = client().queryHistory(source, "7", "meter0/ActivePower",
                "avg", null, Instant.parse("2026-08-07T00:00:00Z"), Instant.parse("2026-08-07T00:10:00Z"),
                300, "MEAN", 28800);

        assertEquals(2, rows.size());
        assertEquals("12.25", rows.get(0).getValue().toString());
        assertTrue(flux.get().contains("r._measurement == \"avg\""));
        assertTrue(flux.get().contains("r[\"edge\"] == \"7\""));
        assertTrue(flux.get().contains("aggregateWindow(every: 300s, fn: mean"));
    }

    private OpenemsInfluxQueryClient client()
    {
        return new OpenemsInfluxQueryClient(new OpenemsCredentialResolver(new MockEnvironment()));
    }

    private EmsOpenemsEndpointSource source(String version)
    {
        EmsOpenemsEndpointSource source = new EmsOpenemsEndpointSource();
        source.setSourceType("RAW_INFLUX");
        source.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        source.setVersion(version);
        source.setOrg("-");
        source.setBucket("openems");
        source.setDatabaseName("openems");
        source.setMeasurement("data");
        source.setEdgeTag("edge");
        source.setCredentialRef("sys:openems.test.influx.query.credential");
        source.setConnectTimeoutSeconds(2);
        source.setReadTimeoutSeconds(2);
        source.setEnabled("0");
        return source;
    }

    private String queryParam(String query, String name) throws IOException
    {
        for (String pair : query.split("&"))
        {
            String[] parts = pair.split("=", 2);
            if (name.equals(URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())))
            {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name());
            }
        }
        return null;
    }

    private String read(InputStream input) throws IOException
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) >= 0)
        {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody())
        {
            output.write(bytes);
        }
    }
}
