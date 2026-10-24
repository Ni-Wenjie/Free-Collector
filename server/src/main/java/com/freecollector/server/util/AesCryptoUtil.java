package com.freecollector.server.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES 加解密工具（安全约束#3）
 *
 * 用途：Cookie、代理账号密码、第三方打码平台密钥等敏感数据，
 *       入库前必须调用 encrypt()，读出后调用 decrypt()，数据库只存密文。
 *
 * 密钥管理：
 *  - 算法 AES/GCM/NoPadding（带完整性校验，防篡改）；
 *  - 密钥为首次运行时自动生成的 32 字节随机数，保存在用户目录 ~/.free_collector/aes.key；
 *  - 密钥文件只存本机、不入数据库、不上传；删除该文件将导致历史密文无法解密。
 */
public final class AesCryptoUtil {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int KEY_LENGTH = 32;        // AES-256
    private static final int IV_LENGTH = 12;         // GCM 推荐 12 字节 IV
    private static final int TAG_LENGTH_BIT = 128;   // GCM 认证标签长度

    private static final Path KEY_FILE = Paths.get(
            System.getProperty("user.home"), ".free_collector", "aes.key");

    /** 密钥懒加载缓存（双重检查锁） */
    private static volatile SecretKeySpec cachedKey;

    private AesCryptoUtil() {
    }

    /**
     * 加密：输出 Base64( IV + 密文 )，IV 每次随机生成，同一明文每次密文不同
     */
    public static String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, loadKey(), new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // IV 不需要保密，与密文拼接后一起 Base64，解密时取前 12 字节还原
            byte[] combined = ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array();
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES加密失败", e);
        }
    }

    /**
     * 解密：从 Base64( IV + 密文 ) 还原明文
     */
    public static String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return cipherText;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, loadKey(), new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES解密失败（密文损坏或密钥缺失）", e);
        }
    }

    /**
     * 加载本机密钥：文件不存在时自动生成并落盘
     */
    private static SecretKeySpec loadKey() {
        if (cachedKey == null) {
            synchronized (AesCryptoUtil.class) {
                if (cachedKey == null) {
                    cachedKey = new SecretKeySpec(readOrCreateKeyFile(), "AES");
                }
            }
        }
        return cachedKey;
    }

    private static byte[] readOrCreateKeyFile() {
        try {
            if (Files.exists(KEY_FILE)) {
                return Base64.getDecoder().decode(Files.readString(KEY_FILE).trim());
            }
            // 首次运行：生成随机密钥并保存到用户目录
            byte[] key = new byte[KEY_LENGTH];
            new SecureRandom().nextBytes(key);
            Files.createDirectories(KEY_FILE.getParent());
            Files.writeString(KEY_FILE, Base64.getEncoder().encodeToString(key));
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("AES密钥文件读写失败: " + KEY_FILE, e);
        }
    }
}
