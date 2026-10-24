package com.freecollector.server.module.crawler.engine;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 访问标识（User-Agent）提供器
 * 普通模式下"自动轮换访问标识"开关控制是否启用
 */
@Component
public class UaProvider {

    /** 常见桌面/移动浏览器UA池，仅用于降低单一标识的识别度 */
    private static final List<String> UA_POOL = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Edg/122.0.0.0",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0"
    );

    /** 随机取一个UA */
    public String pick() {
        return UA_POOL.get(ThreadLocalRandom.current().nextInt(UA_POOL.size()));
    }

    /**
     * 解析本次应使用的UA
     * @param rotate     是否开启自动轮换
     * @param overrideUa 高阶模式手动指定的UA
     * @return null 表示使用浏览器默认UA
     */
    public String resolve(boolean rotate, String overrideUa) {
        if (rotate) {
            return pick();
        }
        if (overrideUa != null && !overrideUa.isBlank()) {
            return overrideUa;
        }
        return null;
    }

    /** UA池快照，供高阶面板展示 */
    public List<String> all() {
        return UA_POOL;
    }
}
