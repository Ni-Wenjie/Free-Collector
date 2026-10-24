package com.freecollector.server.module.crawler.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 爬取策略工厂：根据 SiteType 选择对应策略实例
 *
 * 设计：
 *  - GenericCrawlStrategy 作为默认兜底（所有类型都能跑）
 *  - 各专用策略继承 GenericCrawlStrategy 并覆盖特定方法
 *  - 新增网站类型只需加枚举值 + 注册 Strategy Bean
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrawlStrategyFactory {

    private final GenericCrawlStrategy genericStrategy;
    private final List<CrawlStrategy> allStrategies;

    /** 缓存：SiteType → Strategy */
    private volatile Map<SiteType, CrawlStrategy> strategyCache;

    /**
     * 获取指定网站类型的策略实例
     *
     * @param siteType 网站类型
     * @return 策略实例（永远不为 null）
     */
    public CrawlStrategy getStrategy(SiteType siteType) {
        if (siteType == null) {
            return genericStrategy;
        }
        Map<SiteType, CrawlStrategy> cache = getOrBuildCache();
        return cache.getOrDefault(siteType, genericStrategy);
    }

    /**
     * 通用兜底策略（直接访问，用于 MEDIA_ONLY 等简单模式）
     */
    public GenericCrawlStrategy getGenericStrategy() {
        return genericStrategy;
    }

    private Map<SiteType, CrawlStrategy> getOrBuildCache() {
        if (strategyCache != null) {
            return strategyCache;
        }
        synchronized (this) {
            if (strategyCache == null) {
                Map<SiteType, CrawlStrategy> map = new EnumMap<>(SiteType.class);
                for (CrawlStrategy s : allStrategies) {
                    map.put(s.getSiteType(), s);
                    log.info("注册爬取策略: {} → {}", s.getSiteType(), s.getClass().getSimpleName());
                }
                // 确保 GENERIC 类型有兜底
                map.putIfAbsent(SiteType.GENERIC, genericStrategy);
                strategyCache = map;
            }
        }
        return strategyCache;
    }
}
