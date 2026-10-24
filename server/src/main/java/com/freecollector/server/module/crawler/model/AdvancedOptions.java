package com.freecollector.server.module.crawler.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 高阶模式参数（普通模式下为 null，全部走默认值）
 * 对应前端"高阶面板"，默认折叠隐藏
 *
 * 持久化说明：任务的高级配置以 JSON 存于 crawl_task.advanced_config，
 * 其中还包含 maxPages 等任务级键，因此反序列化时忽略未知属性。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdvancedOptions {

    /** 并发数量（模块五任务队列真正使用；内核单次调用不使用） */
    private Integer concurrency;

    /** 页面加载超时（毫秒） */
    private Integer pageTimeoutMs = 30000;

    /** 失败重试次数（不含首次） */
    private Integer retryTimes = 2;

    /** 等待策略：LOAD / DOMCONTENTLOADED / NETWORKIDLE */
    private String waitUntil = "DOMCONTENTLOADED";

    /** 额外等待的CSS选择器（元素出现即认为就绪） */
    private String waitSelector;

    /** 自定义等待JS：返回 true 表示就绪，轮询执行 */
    private String waitScript;

    /** 自定义请求头 */
    private Map<String, String> extraHeaders;

    /** Cookie 编辑（运行时注入上下文，不落明文库） */
    private List<CookieItem> cookies;

    /** 自定义JS脚本注入，页面加载后依次执行 */
    private List<String> injectScripts;

    /** 覆盖UA（开启自动轮换时忽略此项） */
    private String userAgent;

    /** 代理账号（配合任务级 proxyUrl 使用） */
    private String proxyUsername;

    /** 代理密码（来自加密存储，运行时解密） */
    private String proxyPassword;

    /** 是否无头模式，null 则用全局配置 */
    private Boolean headless;

    /** 是否忽略HTTPS证书错误（自签证书站点用） */
    private Boolean ignoreHttpsErrors;

    /** 是否拦截图片资源以加速（高阶参数） */
    private Boolean blockImages;

    /** 是否拦截音视频资源以加速 */
    private Boolean blockMedia;

    /**
     * Cookie 项
     */
    @Data
    public static class CookieItem {
        private String name;
        private String value;
        private String domain;
        private String path = "/";
        private String url;
    }
}
