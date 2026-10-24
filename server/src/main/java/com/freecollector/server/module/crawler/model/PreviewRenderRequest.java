package com.freecollector.server.module.crawler.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 页面预览渲染请求
 * <p>
 * 用于前端可视化元素选取：后端用 Playwright 打开目标页面，等待渲染完成后
 * 返回完整 HTML。前端用 iframe 的 srcdoc 同源加载，从而可以注入自定义
 * CSS/JS 实现元素高亮和点击选取，绕开目标站 X-Frame-Options 限制。
 */
@Data
public class PreviewRenderRequest {

    /** 目标页面地址，仅允许 http/https */
    @NotBlank(message = "网址不能为空")
    private String url;

    /** 等待选择器：页面出现该元素后再返回 HTML（可选） */
    private String waitSelector;

    /** 额外等待毫秒数（可选，默认 0），用于 JS 延迟渲染场景 */
    private Integer waitMs;

    /** 模拟真人浏览：随机停顿 + 页面滚动 */
    private boolean simulateHuman = false;

    /** 自动轮换 UA */
    private boolean rotateUa = true;

    /** 登录 Cookie（可选，格式 a=1; b=2），用于渲染需要登录的页面 */
    private String cookie;

    /**
     * 转换为 CrawlPageRequest 以复用 CrawlOptions.from 的参数解析与安全校验
     */
    public CrawlPageRequest toCrawlPageRequest() {
        CrawlPageRequest req = new CrawlPageRequest();
        req.setUrl(this.url);
        req.setSimulateHuman(this.simulateHuman);
        req.setRotateUa(this.rotateUa);
        req.setCookie(this.cookie);
        // 预览统一用慢速档位，避免请求过快
        req.setSpeedLevel("SLOW");
        // 等待选择器走高阶参数通道
        if (this.waitSelector != null && !this.waitSelector.isBlank()) {
            AdvancedOptions adv = new AdvancedOptions();
            adv.setWaitSelector(this.waitSelector);
            req.setAdvanced(adv);
        }
        return req;
    }
}
