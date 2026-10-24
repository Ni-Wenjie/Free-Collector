package com.freecollector.server;

import com.freecollector.server.util.AesCryptoUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * AES 加解密工具单元测试（不依赖数据库/Redis，可直接运行）
 */
class AesCryptoUtilTest {

    @Test
    void encryptDecryptRoundTrip() {
        String plain = "打码平台密钥：abc123中文测试";
        String cipher = AesCryptoUtil.encrypt(plain);

        // 断言1：密文不等于明文，且为Base64形态
        assertNotEquals(plain, cipher);
        // 断言2：解密后还原明文（安全约束#3 核心验证）
        assertEquals(plain, AesCryptoUtil.decrypt(cipher));
    }

    @Test
    void samePlainDifferentCipher() {
        // GCM 每次随机IV：同一明文两次加密密文应不同，但都能解出同一明文
        String plain = "proxy:password@127.0.0.1:8888";
        String c1 = AesCryptoUtil.encrypt(plain);
        String c2 = AesCryptoUtil.encrypt(plain);
        assertNotEquals(c1, c2);
        assertEquals(plain, AesCryptoUtil.decrypt(c1));
        assertEquals(plain, AesCryptoUtil.decrypt(c2));
    }

    @Test
    void emptyAndNullSafe() {
        // 空值/NULL 直接透传，避免入库前判空样板代码
        assertEquals("", AesCryptoUtil.encrypt(""));
        assertEquals(null, AesCryptoUtil.encrypt(null));
        assertEquals(null, AesCryptoUtil.decrypt(null));
    }
}
