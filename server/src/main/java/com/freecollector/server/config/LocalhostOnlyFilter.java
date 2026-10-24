package com.freecollector.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 本机访问守卫过滤器（安全约束#2 的第二道防线）
 *
 * 第一道防线：application.yml 中 server.address=127.0.0.1，socket 层面外部就连接不进来；
 * 本过滤器在应用层再校验一次请求来源 IP，防止误配置或代理场景下的外部访问。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalhostOnlyFilter extends OncePerRequestFilter {

    /** 允许的回环地址（IPv4 / IPv6） */
    private static final String[] LOOPBACK = {"127.0.0.1", "0:0:0:0:0:0:0:1", "::1"};

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String remoteAddr = request.getRemoteAddr();
        for (String allowed : LOOPBACK) {
            if (allowed.equals(remoteAddr)) {
                filterChain.doFilter(request, response);
                return;
            }
        }
        // 非本机请求：直接拒绝，返回统一JSON结构
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(
                "{\"code\":1003,\"message\":\"仅允许本机访问\",\"data\":null}".getBytes(StandardCharsets.UTF_8));
    }
}
