package com.freecollector.server.module.risk.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * robots.txt 校验服务（合规风控）
 *
 * 职责：由本机主动拉取目标站点 robots.txt，判断指定 URL 是否允许抓取。
 * 说明：
 *  - 只做轻量解析（User-agent 分组 + Allow/Disallow 前缀匹配，支持 * 与 $ 通配）；
 *  - 结果按站点缓存，避免每次采集都重复请求；
 *  - robots.txt 不存在（404/410）按"允许全部"处理；网络异常同样放行但标记原因，
 *    由上层在界面提示，不因网络波动阻断本地采集。
 *  - 遵循安全约束：请求由本机直接发起，不经过任何中转服务。
 */
@Slf4j
@Service
public class RobotsTxtService {

    /** 本软件在 robots.txt 中匹配的 User-agent 名称 */
    private static final String CRAWLER_AGENT = "freecollector";

    /** 抓取 robots.txt 的超时时间 */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);

    /** 正常结果的缓存时长：30 分钟 */
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    /** 抓取失败结果的缓存时长：5 分钟（避免网络异常时反复卡顿） */
    private static final long FAIL_CACHE_TTL_MS = 5 * 60 * 1000L;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(FETCH_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 站点 robots 缓存：key=协议+主机+端口 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 校验 URL 是否允许抓取
     *
     * @param url 目标页面地址（仅支持 http/https）
     * @return 校验结论（allowed / reason / 命中的规则 / 是否真实抓取过 robots.txt）
     */
    public Decision check(String url) {
        long start = System.currentTimeMillis();
        Decision decision = new Decision();
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https")) {
                decision.setAllowed(false);
                decision.setReason("仅支持 http/https 地址");
                return decision;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                decision.setAllowed(false);
                decision.setReason("目标地址缺少主机名");
                return decision;
            }

            String origin = scheme + "://" + host + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            String robotsUrl = origin + "/robots.txt";
            decision.setRobotsUrl(robotsUrl);

            CacheEntry entry = load(origin, robotsUrl);
            decision.setFetched(entry.fetched);

            if (entry.error != null) {
                // 拉取失败不阻断采集，但把原因带回前端提示
                decision.setAllowed(true);
                decision.setReason(entry.error);
                return decision;
            }
            if (!entry.fetched) {
                // robots.txt 不存在：视为无限制
                decision.setAllowed(true);
                decision.setReason("站点未提供 robots.txt，按允许处理");
                return decision;
            }

            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
                path = path + "?" + uri.getRawQuery();
            }
            Rule matched = match(entry.rules, path);
            if (matched == null) {
                decision.setAllowed(true);
                decision.setReason("robots.txt 未限制该路径");
            } else {
                decision.setAllowed(matched.allow);
                decision.setMatchedRule((matched.allow ? "Allow: " : "Disallow: ") + matched.pattern);
                decision.setReason(matched.allow
                        ? "robots.txt 明确允许该路径"
                        : "robots.txt 禁止抓取该路径，请更换地址或关闭校验后重试");
            }
            return decision;
        } catch (IllegalArgumentException e) {
            decision.setAllowed(false);
            decision.setReason("目标地址格式不合法");
            return decision;
        } catch (Exception e) {
            log.warn("robots 校验异常 url={} error={}", url, e.getMessage());
            decision.setAllowed(true);
            decision.setReason("robots 校验异常，已按放行处理：" + e.getMessage());
            return decision;
        } finally {
            decision.setElapsedMs(System.currentTimeMillis() - start);
        }
    }

    /** 读取（或抓取）站点 robots 规则，带缓存 */
    private CacheEntry load(String origin, String robotsUrl) {
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(origin);
        if (cached != null && cached.expireAt > now) {
            return cached;
        }
        CacheEntry entry = fetch(robotsUrl);
        entry.expireAt = now + (entry.error == null ? CACHE_TTL_MS : FAIL_CACHE_TTL_MS);
        cache.put(origin, entry);
        return entry;
    }

    /** 实际抓取并解析 robots.txt */
    private CacheEntry fetch(String robotsUrl) {
        CacheEntry entry = new CacheEntry();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(robotsUrl))
                    .timeout(FETCH_TIMEOUT)
                    .header("User-Agent", "FreeCollector/1.0 (local compliance check)")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 404 || status == 410) {
                entry.fetched = false;
                return entry;
            }
            if (status < 200 || status >= 300) {
                entry.fetched = false;
                entry.error = "robots.txt 返回状态码 " + status + "，已按放行处理";
                return entry;
            }
            entry.fetched = true;
            entry.rules = parse(response.body());
            return entry;
        } catch (Exception e) {
            entry.fetched = false;
            entry.error = "无法读取 robots.txt（" + e.getClass().getSimpleName() + "），已按放行处理";
            log.info("robots.txt 抓取失败 url={} error={}", robotsUrl, e.getMessage());
            return entry;
        }
    }

    /**
     * 解析 robots.txt 文本为规则列表
     * 只保留适用于本软件（User-agent: * 或 FreeCollector）的分组
     */
    private List<Rule> parse(String content) {
        List<Rule> rules = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return rules;
        }
        List<String> agents = new ArrayList<>();
        boolean agentBlockOpen = false;
        for (String rawLine : content.split("\\r?\\n")) {
            String line = rawLine;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();

            if ("user-agent".equals(key)) {
                // 出现新的 User-agent 且上一组已有规则行，说明进入新的分组
                if (!agentBlockOpen) {
                    agents.clear();
                    agentBlockOpen = true;
                }
                if (!value.isEmpty()) {
                    agents.add(value.toLowerCase());
                }
                continue;
            }
            if (!"allow".equals(key) && !"disallow".equals(key)) {
                continue;
            }
            agentBlockOpen = false;
            if (!appliesToUs(agents)) {
                continue;
            }
            // Disallow: 空值表示不限制任何路径
            if (value.isEmpty()) {
                continue;
            }
            rules.add(new Rule("allow".equals(key), value));
        }
        return rules;
    }

    /** 判断当前分组是否适用于本软件 */
    private boolean appliesToUs(List<String> agents) {
        for (String agent : agents) {
            if (agent.equals("*") || CRAWLER_AGENT.equals(agent) || agent.startsWith(CRAWLER_AGENT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在规则中找出匹配路径的最优规则
     * 规则：最长 pattern 优先；长度相同时 Allow 优先（与主流搜索引擎实现一致）
     */
    private Rule match(List<Rule> rules, String path) {
        Rule best = null;
        for (Rule rule : rules) {
            if (!matches(path, rule.pattern)) {
                continue;
            }
            if (best == null
                    || rule.pattern.length() > best.pattern.length()
                    || (rule.pattern.length() == best.pattern.length() && rule.allow && !best.allow)) {
                best = rule;
            }
        }
        return best;
    }

    /**
     * robots 路径匹配：前缀匹配，支持 * 通配与 $ 结尾锚定
     * 例：/private 匹配 /private/a；/*.php$ 只匹配以 .php 结尾的路径
     */
    private boolean matches(String path, String pattern) {
        String p = pattern;
        boolean anchored = p.endsWith("$");
        if (anchored) {
            p = p.substring(0, p.length() - 1);
        }
        String regex = Arrays.stream(p.split("\\*", -1))
                .map(Pattern::quote)
                .collect(Collectors.joining(".*"));
        if (anchored) {
            regex = regex + "$";
        }
        return Pattern.compile("^" + regex).matcher(path).find();
    }

    /** 清空缓存（设置变更或手动重检时使用） */
    public void clearCache() {
        cache.clear();
    }

    /** 单条 robots 规则 */
    private static class Rule {
        final boolean allow;
        final String pattern;

        Rule(boolean allow, String pattern) {
            this.allow = allow;
            this.pattern = pattern;
        }
    }

    /** 站点级缓存条目 */
    private static class CacheEntry {
        boolean fetched;
        String error;
        List<Rule> rules = new ArrayList<>();
        long expireAt;
    }

    /** 校验结论（返回给前端/调用方） */
    @Data
    public static class Decision {
        /** 是否允许抓取 */
        private boolean allowed = true;
        /** robots.txt 地址 */
        private String robotsUrl;
        /** 命中的规则，如 "Disallow: /admin" */
        private String matchedRule;
        /** 结论说明（面向用户展示） */
        private String reason;
        /** 是否真实获取到 robots.txt（false 表示不存在或读取失败） */
        private boolean fetched;
        /** 校验耗时（毫秒） */
        private long elapsedMs;
    }
}
