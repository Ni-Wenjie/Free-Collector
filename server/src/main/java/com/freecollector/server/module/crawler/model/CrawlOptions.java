package com.freecollector.server.module.crawler.model;

import com.freecollector.server.common.BizException;
import com.freecollector.server.common.ErrorCode;
import lombok.Getter;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 运行时生效的完整采集参数：由 CrawlPageRequest 解析、合并默认值后得到
 * 引擎内部统一使用该对象，避免各层重复判空
 */
@Getter
public class CrawlOptions {

    private String url;
    private SpeedProfile speedProfile;
    private boolean simulateHuman;
    private boolean rotateUa;
    private String proxyUrl;

    private int pageTimeoutMs;
    private int retryTimes;
    private String waitUntil;
    private String waitSelector;
    private String waitScript;
    private String userAgent;
    private boolean headless;
    private boolean ignoreHttpsErrors;
    private boolean blockImages;
    private boolean blockMedia;
    private AdvancedOptions advanced;

    /**
     * 从请求解析参数，并做安全校验
     * 安全约束：仅允许 http/https，禁止 file:// 等本地方案访问
     */
    public static CrawlOptions from(CrawlPageRequest req) {
        CrawlOptions o = new CrawlOptions();
        o.url = req.getUrl();
        // 地址协议白名单：阻断 file:// wecan 等可能读取本机文件的协议
        if (o.url == null || !(o.url.startsWith("http://") || o.url.startsWith("https://"))) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅支持 http/https 网址");
        }

        AdvancedOptions adv = req.getAdvanced() != null ? req.getAdvanced() : new AdvancedOptions();
        o.advanced = adv;
        o.speedProfile = SpeedProfile.parse(req.getSpeedLevel());
        o.simulateHuman = req.isSimulateHuman();
        o.rotateUa = req.isRotateUa();
        o.proxyUrl = req.getProxyUrl();

        // 高阶参数优先，未填写则用安全默认
        o.pageTimeoutMs = adv.getPageTimeoutMs() != null ? adv.getPageTimeoutMs() : 30000;
        o.retryTimes = adv.getRetryTimes() != null ? adv.getRetryTimes() : 2;
        o.waitUntil = adv.getWaitUntil() != null ? adv.getWaitUntil() : "DOMCONTENTLOADED";
        o.waitSelector = adv.getWaitSelector();
        o.waitScript = adv.getWaitScript();
        o.userAgent = adv.getUserAgent();
        o.headless = adv.getHeadless() != null ? adv.getHeadless() : true;
        o.ignoreHttpsErrors = Boolean.TRUE.equals(adv.getIgnoreHttpsErrors());
        o.blockImages = Boolean.TRUE.equals(adv.getBlockImages());
        o.blockMedia = Boolean.TRUE.equals(adv.getBlockMedia());

        // 登录 Cookie：字符串形式（a=1; b=2）解析为上下文 Cookie，仅作用于本次会话
        if (req.getCookie() != null && !req.getCookie().isBlank()
                && (adv.getCookies() == null || adv.getCookies().isEmpty())) {
            adv.setCookies(parseCookieHeader(req.getCookie(), o.url));
        }
        return o;
    }

    /** 将 Cookie 请求头字符串解析为 Playwright 可用的 Cookie 项（按目标站点 origin 绑定） */
    private static List<AdvancedOptions.CookieItem> parseCookieHeader(String header, String url) {
        List<AdvancedOptions.CookieItem> items = new ArrayList<>();
        String origin = originOf(url);
        if (origin == null) {
            return items;
        }
        for (String part : header.split(";")) {
            String seg = part.trim();
            int idx = seg.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String name = seg.substring(0, idx).trim();
            if (name.isEmpty()) {
                continue;
            }
            AdvancedOptions.CookieItem item = new AdvancedOptions.CookieItem();
            item.setName(name);
            item.setValue(seg.substring(idx + 1).trim());
            item.setUrl(origin);
            item.setPath("/");
            items.add(item);
        }
        return items;
    }

    /** 取 URL 的 scheme://host[:port]，作为 Cookie 的绑定地址 */
    private static String originOf(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (Exception e) {
            return null;
        }
    }

    /** 本次请求前的随机停顿（毫秒），由速度档位决定 */
    public int randomDelayMs() {
        int min = speedProfile.getMinDelayMs();
        int max = speedProfile.getMaxDelayMs();
        return min + (int) (Math.random() * (max - min + 1));
    }
}
