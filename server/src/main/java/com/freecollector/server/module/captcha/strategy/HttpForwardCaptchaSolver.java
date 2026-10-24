package com.freecollector.server.module.captcha.strategy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freecollector.server.module.captcha.model.CaptchaConfigModel;
import com.freecollector.server.module.captcha.model.CaptchaSolveRequest;
import com.freecollector.server.module.captcha.model.CaptchaSolveResult;
import com.freecollector.server.module.captcha.util.CaptchaTemplateResolver;
import com.jayway.jsonpath.JsonPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 通用 HTTP 转发打码策略
 *
 * 职责边界（重要）：
 *  - 本软件不内置任何验证码识别模型；
 *  - 只负责：替换图片参数 → 转发到用户配置的第三方接口 → 按JSONPath取结果；
 *  - 图片与请求均从本机主动向外发起，软件不作为中转服务器；
 *  - 密钥、图片内容不写日志；调用明细单独写入 logs/captcha/captcha.log。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpForwardCaptchaSolver implements CaptchaSolverStrategy {

    /** 独立打码审计日志（见 logback-spring.xml） */
    private static final Logger AUDIT = LoggerFactory.getLogger("com.freecollector.captcha.audit");

    private final ObjectMapper objectMapper;

    /** 打码请求独立 HttpClient，不与爬虫页面共享连接池 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String getType() {
        return "HTTP_FORWARD";
    }

    @Override
    public CaptchaSolveResult solve(CaptchaConfigModel config, CaptchaSolveRequest request) {
        long start = System.currentTimeMillis();
        String lastError = null;
        String lastRaw = null;
        int attempts = 0;

        String invalid = validate(config, request);
        if (invalid != null) {
            return auditAndReturn(CaptchaSolveResult.fail(invalid, 0, 0, null), config);
        }

        int timeoutMs = config.getTimeoutMs() == null ? 30000 : config.getTimeoutMs();
        int pollIntervalMs = config.getPollIntervalMs() == null ? 3000 : config.getPollIntervalMs();
        int retryTimes = config.getRetryTimes() == null ? 3 : config.getRetryTimes();

        Map<String, String> variables = buildVariables(config, request);
        String body;
        Map<String, String> headers;
        try {
            body = CaptchaTemplateResolver.renderJsonTemplate(config.getParamTemplate(), variables);
            headers = CaptchaTemplateResolver.parseHeaders(objectMapper, config.getRequestHeaders(), variables);
        } catch (Exception e) {
            return auditAndReturn(CaptchaSolveResult.fail("参数模板格式错误: " + e.getMessage(), 0, 0, null), config);
        }

        long deadline = start + timeoutMs;
        int consecutiveErrors = 0;

        while (System.currentTimeMillis() < deadline) {
            attempts++;
            long remaining = Math.max(1000L, deadline - System.currentTimeMillis());
            try {
                HttpResponse<String> response = send(config, body, headers, remaining);
                lastRaw = response.body();

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    consecutiveErrors = 0;
                    String value = extractResult(lastRaw, config.getResultJsonPath());
                    if (value != null) {
                        long elapsed = System.currentTimeMillis() - start;
                        AUDIT.info("captcha success config={} attempts={} elapsed={}ms valueLength={}",
                                safeName(config), attempts, elapsed, value.length());
                        return CaptchaSolveResult.ok(value, attempts, elapsed, lastRaw);
                    }
                    // 平台返回"处理中"：未取到结果，按轮询间隔继续
                    lastError = "识别结果为空（平台可能仍在处理）";
                } else {
                    consecutiveErrors++;
                    lastError = "接口返回HTTP " + response.statusCode();
                    // 连续非2xx同样计入重试上限，避免无意义轮询到超时
                    if (consecutiveErrors > retryTimes) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "打码请求被中断";
                break;
            } catch (Exception e) {
                consecutiveErrors++;
                lastError = "请求异常: " + e.getMessage();
                // 连续网络错误超过重试次数，直接失败，避免占用爬虫资源
                if (consecutiveErrors > retryTimes) {
                    break;
                }
            }

            // 轮询间隔（加少量抖动，避免多任务同时打码造成尖峰）
            long sleepTime = Math.min(pollIntervalMs + ThreadLocalRandom.current().nextInt(200),
                    Math.max(200L, deadline - System.currentTimeMillis()));
            try {
                Thread.sleep(Math.max(200L, sleepTime));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (lastError == null) {
            lastError = "打码超时（" + timeoutMs + "ms）";
        }
        long elapsed = System.currentTimeMillis() - start;
        AUDIT.warn("captcha failed config={} attempts={} elapsed={}ms error={}",
                safeName(config), attempts, elapsed, lastError);
        return CaptchaSolveResult.fail(lastError, attempts, elapsed, lastRaw);
    }

    /**
     * 发送一次请求
     */
    private HttpResponse<String> send(CaptchaConfigModel config, String renderedBody,
                                      Map<String, String> headers, long timeoutMs)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/json");

        // 自定义请求头（覆盖默认Content-Type）
        boolean hasContentType = false;
        for (Map.Entry<String, String> h : headers.entrySet()) {
            builder.header(h.getKey(), h.getValue());
            if ("content-type".equalsIgnoreCase(h.getKey())) {
                hasContentType = true;
            }
        }

        String method = config.getHttpMethod() == null ? "POST" : config.getHttpMethod().trim().toUpperCase();
        String url = config.getApiUrl();

        if ("GET".equals(method)) {
            // GET：把模板解析为查询参数
            String query = buildQuery(renderedBody);
            String fullUrl = url + (url.contains("?") ? "&" : "?") + query;
            builder.uri(URI.create(fullUrl)).GET();
        } else if ("POST".equals(method)) {
            if (!hasContentType) {
                builder.header("Content-Type", "application/json;charset=UTF-8");
            }
            builder.uri(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString(renderedBody, StandardCharsets.UTF_8));
        } else {
            throw new IllegalArgumentException("不支持的请求方法: " + method);
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * GET 模式：JSON模板 → URL查询字符串
     */
    private String buildQuery(String renderedJson) throws Exception {
        Map<String, Object> params = objectMapper.readValue(renderedJson,
                new TypeReference<Map<String, Object>>() {
                });
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * 按 JSONPath 提取识别结果；取不到返回 null
     */
    private String extractResult(String raw, String jsonPath) {
        if (raw == null || raw.isBlank() || jsonPath == null || jsonPath.isBlank()) {
            return null;
        }
        try {
            Object value = JsonPath.read(raw, jsonPath);
            if (value == null) {
                return null;
            }
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        value = item;
                        break;
                    }
                }
            }
            String text = value instanceof String ? (String) value : objectMapper.writeValueAsString(value);
            text = text == null ? null : text.trim();
            return (text == null || text.isEmpty()) ? null : text;
        } catch (Exception e) {
            // 路径不存在/响应非JSON：视为本次未取到结果，继续轮询
            log.debug("打码结果JSONPath提取失败 path={} error={}", jsonPath, e.getMessage());
            return null;
        }
    }

    /**
     * 构建占位符变量表
     */
    private Map<String, String> buildVariables(CaptchaConfigModel config, CaptchaSolveRequest request) {
        Map<String, String> vars = new HashMap<>();
        putIfNotNull(vars, "image_base64", request.getImageBase64());
        putIfNotNull(vars, "image_url", request.getImageUrl());
        putIfNotNull(vars, "api_key", config.getApiKey());
        if (request.getExtraParams() != null) {
            request.getExtraParams().forEach((k, v) -> {
                if (k != null) {
                    vars.put(k, v == null ? "" : v);
                }
            });
        }
        return vars;
    }

    private void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /**
     * 配置校验：返回错误信息，null表示通过
     */
    private String validate(CaptchaConfigModel config, CaptchaSolveRequest request) {
        if (config == null) {
            return "打码方案配置为空";
        }
        if (config.getApiUrl() == null || !(config.getApiUrl().startsWith("http://")
                || config.getApiUrl().startsWith("https://"))) {
            return "打码接口地址必须是 http/https";
        }
        if (config.getParamTemplate() == null || config.getParamTemplate().isBlank()) {
            return "参数模板不能为空";
        }
        if (config.getResultJsonPath() == null || config.getResultJsonPath().isBlank()) {
            return "结果提取JSONPath不能为空";
        }
        boolean hasImage = (request.getImageBase64() != null && !request.getImageBase64().isBlank())
                || (request.getImageUrl() != null && !request.getImageUrl().isBlank());
        if (!hasImage) {
            return "待识别图片为空（base64与url至少提供一个）";
        }
        return null;
    }

    /** 审计日志里只记录方案名，不记录URL中的密钥 */
    private String safeName(CaptchaConfigModel config) {
        return config.getConfigName() == null ? "-" : config.getConfigName();
    }

    /** 统一写审计日志后返回 */
    private CaptchaSolveResult auditAndReturn(CaptchaSolveResult result, CaptchaConfigModel config) {
        AUDIT.warn("captcha invalid config={} error={}", safeName(config), result.getErrorMsg());
        return result;
    }
}
