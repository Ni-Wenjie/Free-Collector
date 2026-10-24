package com.freecollector.server.module.crawler.engine;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.freecollector.server.module.crawler.model.PaginationInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分页识别器：按常见"下一页"特征自动探测
 * 识别结果写回任务规则，后续翻页由任务调度模块执行
 */
@Slf4j
@Component
public class PaginationDetector {

    /** 常见分页入口候选选择器，按命中优先级排序 */
    private static final List<String> CANDIDATES = List.of(
            "a:has-text(\"下一页\")",
            "a:has-text(\"下页\")",
            "a:has-text(\"Next\")",
            "button:has-text(\"下一页\")",
            ".pagination .next",
            ".pagination-next",
            ".layui-laypage-next",
            "a.next",
            "[rel=next]"
    );

    /**
     * 探测下一页入口
     */
    public PaginationInfo detect(Page page) {
        for (String selector : CANDIDATES) {
            try {
                Locator locator = page.locator(selector);
                if (locator.count() == 0) {
                    continue;
                }
                Locator first = locator.first();
                if (!first.isVisible()) {
                    continue;
                }

                PaginationInfo info = new PaginationInfo();
                info.setFound(true);
                info.setSelector(selector);
                info.setText(safeText(first));
                info.setHref(safeAttr(first, "href"));
                info.setHasNext(!isDisabled(first));
                return info;
            } catch (Exception e) {
                // 候选选择器在当前页面不适用，继续尝试下一个
                log.debug("分页候选选择器探测失败 selector={}", selector);
            }
        }
        return PaginationInfo.none();
    }

    /** 文本获取失败时返回null */
    private String safeText(Locator locator) {
        try {
            return locator.innerText();
        } catch (Exception e) {
            return null;
        }
    }

    /** 属性获取失败时返回null */
    private String safeAttr(Locator locator, String attr) {
        try {
            return locator.getAttribute(attr);
        } catch (Exception e) {
            return null;
        }
    }

    /** 判断是否禁用：class含disabled 或 aria-disabled=true */
    private boolean isDisabled(Locator locator) {
        try {
            String cls = locator.getAttribute("class");
            String aria = locator.getAttribute("aria-disabled");
            return (cls != null && cls.toLowerCase().contains("disabled"))
                    || "true".equalsIgnoreCase(aria);
        } catch (Exception e) {
            return false;
        }
    }
}
