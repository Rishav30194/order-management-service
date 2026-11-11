package com.rishav.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Replaces the routes with one to a local stub, so the default filters from application.yml run against it.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRetryTest {

    private static final HttpServer STUB = startStub();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static volatile int failuresBeforeSuccess;

    @LocalServerPort
    private int port;

    @Autowired
    private HttpClientProperties httpClientProperties;

    private final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void stubRoute(DynamicPropertyRegistry registry) {
        String prefix = "spring.cloud.gateway.server.webflux.routes[0].";
        registry.add(prefix + "id", () -> "stub");
        registry.add(prefix + "uri", () -> "http://localhost:" + STUB.getAddress().getPort());
        registry.add(prefix + "predicates[0]", () -> "Path=/stub/**");
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @BeforeEach
    void resetStub() {
        CALLS.set(0);
    }

    @Test
    void get_serverErrors_areRetried() throws Exception {
        failuresBeforeSuccess = 2;

        assertEquals(200, send("GET").statusCode());
        assertEquals(3, CALLS.get());
    }

    @Test
    void post_serverError_isNotRetried() throws Exception {
        failuresBeforeSuccess = 2;

        assertEquals(503, send("POST").statusCode());
        assertEquals(1, CALLS.get());
    }

    @Test
    void timeLimits_areSet() {
        assertEquals(2000, httpClientProperties.getConnectTimeout());
        assertEquals(Duration.ofSeconds(30), httpClientProperties.getResponseTimeout());
    }

    private HttpResponse<String> send(String method) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/stub/thing"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                int status = CALLS.incrementAndGet() <= failuresBeforeSuccess ? 503 : 200;
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
