package com.freecollector.server.module.task.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * 数据去重指纹工具
 *
 * 指纹算法：把所有字段按名称排序后拼接 "key=value&" 字符串，再取 SHA-256。
 * 这样字段顺序不同但内容相同的记录会得到相同指纹，供模块六清洗去重使用。
 */
public final class DedupHashUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DedupHashUtil() {
    }

    /**
     * 计算一行数据的去重指纹
     */
    public static String hash(Map<String, String> row) {
        try {
            // 排序保证字段顺序不影响结果
            TreeMap<String, String> sorted = new TreeMap<>(row);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                sb.append(entry.getKey()).append('=')
                        .append(entry.getValue() == null ? "" : entry.getValue())
                        .append('&');
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /** 行数据序列化为 JSON 字符串 */
    public static String toJson(Map<String, String> row) {
        try {
            return MAPPER.writeValueAsString(row);
        } catch (Exception e) {
            throw new IllegalStateException("行数据序列化失败", e);
        }
    }
}
