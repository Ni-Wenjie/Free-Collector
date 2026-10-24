package com.freecollector.server.module.crawler.engine;

import com.microsoft.playwright.Page;
import com.freecollector.server.module.crawler.model.CrawlOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 真人浏览模拟：随机停顿 + 分段滚动
 * 仅在"模拟真人浏览"开关开启时执行滚动；请求间隔始终按速度档位执行
 */
@Slf4j
@Component
public class HumanBehaviorSimulator {

    /**
     * 执行模拟行为并等待请求间隔
     */
    public void simulate(Page page, CrawlOptions options) {
        if (options.isSimulateHuman()) {
            int scrollSteps = ThreadLocalRandom.current().nextInt(2, 4);
            for (int i = 0; i < scrollSteps; i++) {
                int distance = ThreadLocalRandom.current().nextInt(300, 700);
                try {
                    page.evaluate("window.scrollBy(0, " + distance + ")");
                } catch (Exception e) {
                    // 页面可能已跳转/关闭，滚动失败不影响后续提取
                    log.debug("页面滚动失败（忽略）: {}", e.getMessage());
                }
                sleep(ThreadLocalRandom.current().nextInt(300, 900));
            }
        }
        // 速度档位决定的请求间隔，避免高频访问
        sleep(options.randomDelayMs());
    }

    /** 可中断休眠，便于后续任务终止时快速退出 */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
