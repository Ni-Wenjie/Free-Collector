package com.freecollector.server.module.crawler.engine;

import com.freecollector.server.module.crawler.config.CrawlerProperties;
import com.freecollector.server.module.crawler.model.AdvancedOptions;
import com.freecollector.server.module.crawler.model.CrawlOptions;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话工厂：按任务参数创建隔离的 Playwright 会话
 *
 * 并发说明：Playwright Java 对象非线程安全，因此"一个任务一个会话"，
 * 并发数由任务调度模块（模块五）按速度档位限制，避免共享对象引发的竞态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaywrightSessionFactory {

    private final BrowserInstaller browserInstaller;
    private final UaProvider uaProvider;
    private final CrawlerProperties properties;

    /**
     * 打开一个隔离会话
     */
    public PlaywrightSession open(CrawlOptions options) {
        // 首次使用自动准备内置浏览器
        browserInstaller.ensureInstalled();

        Playwright playwright = Playwright.create();
        try {
            Browser browser = playwright.chromium().launch(buildLaunchOptions(options));
            BrowserContext context = browser.newContext(buildContextOptions(options));
            applyContextSecurity(context, options);
            applyCookies(context, options);

            Page page = context.newPage();
            page.setDefaultTimeout(options.getPageTimeoutMs());
            // 禁止弹窗逃逸：任何新窗口立即关闭，避免恶意页面跳出沙箱视野
            page.onPopup(Page::close);
            return new PlaywrightSession(playwright, browser, context, page);
        } catch (Exception e) {
            // 启动失败必须回收 Playwright，否则底层驱动进程残留
            try {
                playwright.close();
            } catch (Exception ignore) {
                // 忽略二次异常
            }
            throw e;
        }
    }

    /** 启动参数：保留 Chromium 自带沙箱，不传 --no-sandbox */
    private BrowserType.LaunchOptions buildLaunchOptions(CrawlOptions options) {
        List<String> args = new ArrayList<>();
        args.add("--disable-dev-shm-usage");   // 内存受限环境稳定运行
        args.add("--no-first-run");            // 跳过首次启动引导
        args.add("--disable-notifications");   // 关闭系统通知

        // 无头模式默认走全局配置；高阶面板可显式覆盖
        boolean headless = options.getAdvanced() != null && options.getAdvanced().getHeadless() != null
                ? options.getAdvanced().getHeadless()
                : properties.isHeadless();

        return new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setArgs(args);
    }

    /** 上下文参数：UA、语言、视口、代理、HTTPS策略 */
    private Browser.NewContextOptions buildContextOptions(CrawlOptions options) {
        Browser.NewContextOptions ctx = new Browser.NewContextOptions()
                .setViewportSize(1366, 800)
                .setLocale("zh-CN")
                .setAcceptDownloads(false)   // 禁止下载，避免写入本机磁盘
                .setIgnoreHTTPSErrors(options.isIgnoreHttpsErrors());

        // 自动轮换UA 或 高阶指定UA
        String ua = uaProvider.resolve(options.isRotateUa(), options.getUserAgent());
        if (ua != null) {
            ctx.setUserAgent(ua);
        }

        // 请求头：默认语言头 + 高阶自定义请求头
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        if (options.getAdvanced() != null && options.getAdvanced().getExtraHeaders() != null) {
            headers.putAll(options.getAdvanced().getExtraHeaders());
        }
        ctx.setExtraHTTPHeaders(headers);

        // 代理：任务级地址 + 高阶账号密码
        if (options.getProxyUrl() != null && !options.getProxyUrl().isBlank()) {
            com.microsoft.playwright.options.Proxy proxy =
                    new com.microsoft.playwright.options.Proxy(options.getProxyUrl());
            if (options.getAdvanced() != null) {
                if (options.getAdvanced().getProxyUsername() != null) {
                    proxy.setUsername(options.getAdvanced().getProxyUsername());
                }
                if (options.getAdvanced().getProxyPassword() != null) {
                    proxy.setPassword(options.getAdvanced().getProxyPassword());
                }
            }
            ctx.setProxy(proxy);
        }
        return ctx;
    }

    /**
     * 上下文安全策略：
     * 1) 只放行 http/https/data/about/blob 协议；
     * 2) 可按高阶配置拦截图片/音视频资源以提速；
     * 3) 统一在路由层处理，页面JS无法绕过。
     */
    private void applyContextSecurity(BrowserContext context, CrawlOptions options) {
        context.route("**/*", route -> {
            String url = route.request().url();
            boolean allowedScheme = url.startsWith("http://") || url.startsWith("https://")
                    || url.startsWith("data:") || url.startsWith("about:") || url.startsWith("blob:");
            if (!allowedScheme) {
                // 阻断 file:// ftp:// 等协议，防止读取本机文件
                route.abort();
                return;
            }
            String type = route.request().resourceType();
            if (options.isBlockImages() && "image".equals(type)) {
                route.abort();
                return;
            }
            if (options.isBlockMedia() && ("media".equals(type) || "font".equals(type))) {
                route.abort();
                return;
            }
            route.resume();
        });
    }

    /** 注入高阶模式编辑的 Cookie（值在运行时来自解密，不写日志） */
    private void applyCookies(BrowserContext context, CrawlOptions options) {
        if (options.getAdvanced() == null || options.getAdvanced().getCookies() == null
                || options.getAdvanced().getCookies().isEmpty()) {
            return;
        }
        List<com.microsoft.playwright.options.Cookie> cookies = new ArrayList<>();
        for (AdvancedOptions.CookieItem item : options.getAdvanced().getCookies()) {
            if (item.getName() == null || item.getValue() == null) {
                continue;
            }
            com.microsoft.playwright.options.Cookie cookie =
                    new com.microsoft.playwright.options.Cookie(item.getName(), item.getValue());
            if (item.getUrl() != null && !item.getUrl().isBlank()) {
                cookie.setUrl(item.getUrl());
            } else {
                cookie.setDomain(item.getDomain());
                cookie.setPath(item.getPath() == null ? "/" : item.getPath());
            }
            cookies.add(cookie);
        }
        if (!cookies.isEmpty()) {
            context.addCookies(cookies);
        }
    }
}
