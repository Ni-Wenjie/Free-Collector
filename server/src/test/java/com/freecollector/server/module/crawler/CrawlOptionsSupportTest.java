package com.freecollector.server.module.crawler;

import com.freecollector.server.module.crawler.engine.UaProvider;
import com.freecollector.server.module.crawler.model.SpeedProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 访问速度档位与UA轮换单元测试（不依赖浏览器）
 */
class CrawlOptionsSupportTest {

    @Test
    void speedProfileDefaultsToSlow() {
        // 未知/空值必须回落到最安全的慢速档
        assertEquals(SpeedProfile.SLOW, SpeedProfile.parse(null));
        assertEquals(SpeedProfile.SLOW, SpeedProfile.parse(""));
        assertEquals(SpeedProfile.SLOW, SpeedProfile.parse("UNKNOWN"));
        assertEquals(SpeedProfile.FAST, SpeedProfile.parse("fast"));
    }

    @Test
    void speedProfileDelayRangeValid() {
        for (SpeedProfile p : SpeedProfile.values()) {
            assertTrue(p.getMinDelayMs() > 0);
            assertTrue(p.getMaxDelayMs() >= p.getMinDelayMs());
            assertTrue(p.getConcurrency() >= 1);
            assertNotNull(p.getDescription());
        }
    }

    @Test
    void uaResolveRules() {
        UaProvider provider = new UaProvider();
        // 未开启轮换、未指定UA → 使用浏览器默认（null）
        assertNull(provider.resolve(false, null));
        // 高阶手动指定UA优先
        assertEquals("CustomUA", provider.resolve(false, "CustomUA"));
        // 开启轮换时忽略手动UA，从池中取
        String rotated = provider.resolve(true, "CustomUA");
        assertNotNull(rotated);
        assertTrue(provider.all().contains(rotated));
    }
}
