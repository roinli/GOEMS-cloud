package com.witos.ems.server.openems;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.witos.ems.server.domain.entity.EmsOpenemsEndpointSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenemsInfluxConnectionTesterTest
{
    private HttpServer server;

    @AfterEach
    void tearDown()
    {
        System.clearProperty("openems.test.influx.credential");
        if (server != null)
        {
            server.stop(0);
        }
    }

    @Test
    void influxV1ChecksEdgeTagThenQueriesSampleEdge() throws Exception
    {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/query", exchange -> {
            int call = calls.incrementAndGet();
            String body = call == 1
                    ? "{\"results\":[{\"series\":[{\"columns\":[\"key\",\"value\"],\"values\":[[\"edge\",\"edge10001\"]]}]}]}"
                    : "{\"results\":[{\"series\":[{\"columns\":[\"time\",\"value\"],\"values\":[[\"2026-08-07T00:00:00Z\",1]]}]}]}";
            respond(exchange, 200, body);
        });
        server.start();
        System.setProperty("openems.test.influx.credential", "user:password");

        Map<String, Object> result = tester().test(source("INFLUX_1"));

        assertTrue((Boolean) result.get("success"));
        assertEquals("SUCCESS", result.get("status"));
        assertEquals("edge10001", result.get("sampleEdgeId"));
        assertTrue((Boolean) result.get("dataAvailable"));
        assertEquals(2, calls.get());
    }

    @Test
    void influxV2UsesFluxQueryAndReportsEmptyWindowWithoutFailure() throws Exception
    {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v2/query", exchange -> respond(exchange, 200,
                "#datatype,string,long,dateTime:RFC3339\n,result,table,_time,_field,_value,edge\n"));
        server.start();
        System.setProperty("openems.test.influx.credential", "token-value");

        EmsOpenemsEndpointSource source = source("INFLUX_2");
        source.setOrg("openems");
        source.setBucket("openems");
        Map<String, Object> result = tester().test(source);

        assertTrue((Boolean) result.get("success"));
        assertEquals("SUCCESS", result.get("status"));
        assertFalse((Boolean) result.get("dataAvailable"));
    }

    @Test
    void disabledAggregatedSourceIsNotConfigured()
    {
        EmsOpenemsEndpointSource source = source("INFLUX_1");
        source.setSourceType("AGGREGATED_INFLUX");
        source.setEnabled("1");

        Map<String, Object> result = tester().test(source);

        assertFalse((Boolean) result.get("success"));
        assertEquals("NOT_CONFIGURED", result.get("status"));
    }

    private OpenemsInfluxConnectionTester tester()
    {
        return new OpenemsInfluxConnectionTester(new OpenemsCredentialResolver(new MockEnvironment()));
    }

    private EmsOpenemsEndpointSource source(String version)
    {
        EmsOpenemsEndpointSource source = new EmsOpenemsEndpointSource();
        source.setSourceType("RAW_INFLUX");
        source.setUrl(server == null ? "http://127.0.0.1:8086" : "http://127.0.0.1:" + server.getAddress().getPort());
        source.setVersion(version);
        source.setOrg("-");
        source.setBucket("openems");
        source.setDatabaseName("openems");
        source.setMeasurement("data");
        source.setEdgeTag("edge");
        source.setCredentialRef("sys:openems.test.influx.credential");
        source.setConnectTimeoutSeconds(2);
        source.setReadTimeoutSeconds(2);
        source.setEnabled("0");
        return source;
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
