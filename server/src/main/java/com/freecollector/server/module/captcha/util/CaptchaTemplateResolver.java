package com.freecollector.server.module.captcha.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * 打码参数模板解析工具
 *
 * 模板示例：
 *   {"username":"{{api_key}}","image":"{{image_base64}}","type":"{{type}}"}
 * 占位符统一写作 {{name}}，替换时会做 JSON 字符串转义，防止模板被撑破。
 */
public final class CaptchaTemplateResolver {

    private CaptchaTemplateResolver() {
    }

    /**
     * 渲染 JSON 模板：占位符替换 + JSON 转义
     */
    public static String renderJsonTemplate(String template, Map<String, String> variables) {
        if (template == null) {
            return null;
        }
        String rendered = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            rendered = rendered.replace("{{" + entry.getKey() + "}}", jsonEscape(entry.getValue()));
        }
        return rendered;
    }

    /**
     * 渲染并解析请求头JSON，返回可直接使用的请求头Map
     */
    public static Map<String, String> parseHeaders(ObjectMapper mapper, String headersJson,
                                                   Map<String, String> variables) {
        if (headersJson == null || headersJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            String rendered = renderJsonTemplate(headersJson, variables);
            return mapper.readValue(rendered, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("请求头JSON格式错误: " + e.getMessage(), e);
        }
    }

    /**
     * JSON 字符串转义：处理引号、反斜杠、控制字符
     */
    public static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
