package com.freecollector.server.module.captcha;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freecollector.server.module.captcha.model.CaptchaConfigModel;
import com.freecollector.server.module.captcha.model.CaptchaSolveRequest;
import com.freecollector.server.module.captcha.model.CaptchaSolveResult;
import com.freecollector.server.module.captcha.strategy.HttpForwardCaptchaSolver;
import com.freecollector.server.module.captcha.util.CaptchaTemplateResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用HTTP转发打码策略测试：用本地 HttpServer 模拟第三方打码平台
 * 不访问外网、不产生任何费用
 */
class HttpForwardCaptchaSolverTest {

    private static HttpServer server;
    private static String baseUrl;

    /** /solve 立即返回结果；/poll 第一次返回空、第二次返回结果 */
    private static final AtomicInteger pollCalls = new AtomicInteger();
    private static final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/solve", exchange -> {
            lastRequestBody.set(readBody(exchange));
            respond(exchange, 200, "{\"code\":0,\"data\":{\"result\":\"8x2k\"}}");
        });

        server.createContext("/poll", exchange -> {
            readBody(exchange);
            int n = pollCalls.incrementAndGet();
            if (n < 2) {
                respond(exchange, 200, "{\"code\":0,\"data\":{}}");
            } else {
                respond(exchange, 200, "{\"code\":0,\"data\":{\"result\":\"ab12\"}}");
            }
        });

        server.createContext("/error", exchange -> respond(exchange, 500, "{\"error\":\"server error\"}"));

        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private CaptchaConfigModel config(String path, String headersJson) {
        CaptchaConfigModel c = new CaptchaConfigModel();
        c.setConfigName("本地测试方案");
        c.setApiUrl(baseUrl + path);
        c.setHttpMethod("POST");
        c.setParamTemplate("{\"image\":\"{{image_base64}}\",\"type\":\"{{type}}\"}");
        c.setResultJsonPath("$.data.result");
        c.setApiKey("test-key");
        c.setTimeoutMs(3000);
        c.setPollIntervalMs(50);
        c.setRequestHeaders(headersJson);
        return c;
    }

    private CaptchaSolveRequest request() {
        CaptchaSolveRequest r = new CaptchaSolveRequest();
        r.setImageBase64("ZmFrZS1pbWFnZS1ieXRlcw==");
        r.setExtraParams(Map.of("type", "4"));
        return r;
    }

    @Test
    void solveSuccessAndTemplateRendered() {
        lastRequestBody.set(null);
        HttpForwardCaptchaSolver solver = new HttpForwardCaptchaSolver(new ObjectMapper());

        CaptchaSolveResult result = solver.solve(config("/solve", null), request());

        assertTrue(result.isSuccess(), () -> "失败原因: " + result.getErrorMsg());
        assertEquals("8x2k", result.getValue());
        // 参数模板占位符已被替换
        assertTrue(lastRequestBody.get().contains("ZmFrZS1pbWFnZS1ieXRlcw=="));
        assertTrue(lastRequestBody.get().contains("\"type\":\"4\""));
    }

    @Test
    void pollingUntilResultReady() {
        pollCalls.set(0);
        HttpForwardCaptchaSolver solver = new HttpForwardCaptchaSolver(new ObjectMapper());

        CaptchaSolveResult result = solver.solve(config("/poll", null), request());

        assertTrue(result.isSuccess());
        assertEquals("ab12", result.getValue());
        assertTrue(result.getAttempts() >= 2, "应至少轮询2次");
    }

    @Test
    void httpErrorReturnsFailureNotException() {
        HttpForwardCaptchaSolver solver = new HttpForwardCaptchaSolver(new ObjectMapper());
        CaptchaSolveResult result = solver.solve(config("/error", null), request());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMsg().contains("HTTP 500"));
    }

    @Test
    void invalidConfigRejected() {
        HttpForwardCaptchaSolver solver = new HttpForwardCaptchaSolver(new ObjectMapper());
        CaptchaConfigModel bad = config("/solve", null);
        bad.setApiUrl("file:///etc/passwd");

        CaptchaSolveResult result = solver.solve(bad, request());

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMsg().contains("http/https"));
    }

    @Test
    void templateEscapingAndHeaders() {
        // 值中含引号/换行，必须被转义成合法JSON
        String rendered = CaptchaTemplateResolver.renderJsonTemplate(
                "{\"key\":\"{{api_key}}\",\"img\":\"{{image_base64}}\"}",
                Map.of("api_key", "a\"b\nc", "image_base64", "abc+/="));
        assertTrue(rendered.contains("a\\\"b\\nc"));
        assertTrue(rendered.contains("abc+/="));

        Map<String, String> headers = CaptchaTemplateResolver.parseHeaders(new ObjectMapper(),
                "{\"Authorization\":\"Bearer {{api_key}}\"}", Map.of("api_key", "test-key"));
        assertEquals("Bearer test-key", headers.get("Authorization"));
    }
}
