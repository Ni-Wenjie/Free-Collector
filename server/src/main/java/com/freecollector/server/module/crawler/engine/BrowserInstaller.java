package com.freecollector.server.module.crawler.engine;

import com.freecollector.server.common.BizException;
import com.freecollector.server.common.ErrorCode;
import com.freecollector.server.module.crawler.config.CrawlerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 内置浏览器安装器
 *
 * 目标：用户不需要手动安装 Chrome 或驱动。
 * Playwright 自带 Chromium，但首次使用需要下载一次浏览器二进制（约120MB）。
 * 本类在首次启动采集前自动完成安装，并做幂等判断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrowserInstaller {

    private final CrawlerProperties properties;

    /** 避免并发重复检查/安装 */
    private volatile boolean checked = false;

    /**
     * 确保浏览器可用；已安装则直接返回
     */
    public void ensureInstalled() {
        if (checked) {
            return;
        }
        synchronized (this) {
            if (checked) {
                return;
            }
            if (isInstalled()) {
                checked = true;
                return;
            }
            if (!properties.isAutoInstallBrowser()) {
                throw new BizException(ErrorCode.CRAWLER_ERROR,
                        "未检测到内置浏览器，且自动安装已关闭。请双击 install-browser.bat 完成安装");
            }
            install();
            checked = true;
        }
    }

    /**
     * 判断 Playwright 默认缓存目录中是否存在 Chromium
     */
    public boolean isInstalled() {
        Path dir = browsersDir();
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            // 目录形如 chromium-1140 / chromium_headless_shell-1140
            return stream.anyMatch(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("chromium-") || name.startsWith("chromium_headless_shell-");
            });
        } catch (Exception e) {
            log.warn("检查浏览器安装目录失败: {}", dir, e);
            return false;
        }
    }

    /**
     * 安装 Chromium。
     * 两种运行形态分别处理：
     *  - 开发/IDE：classpath 为多个路径，直接启动子JVM执行 Playwright CLI；
     *  - 打包后 fat jar：classpath 只有单个jar，CLI 类在嵌套jar中无法直接 -cp 启动，
     *    改为调用本机 Maven 执行同一安装命令（内部测试版工程目录可用）。
     */
    private void install() {
        log.info("首次运行：开始下载内置浏览器 Chromium，请保持网络畅通（约120MB，仅一次）");
        String classpath = System.getProperty("java.class.path", "");
        boolean success = classpath.contains(File.pathSeparator)
                ? installWithClasspath(classpath)
                : installWithMaven();

        if (!success || !isInstalled()) {
            throw new BizException(ErrorCode.CRAWLER_ERROR,
                    "浏览器安装失败。可设置镜像环境变量 PLAYWRIGHT_DOWNLOAD_HOST="
                            + "https://npmmirror.com/mirrors/playwright 后重试，"
                            + "或双击 install-browser.bat 手动安装");
        }
        log.info("内置浏览器安装完成");
    }

    /** 开发模式：子JVM + 完整classpath 执行 CLI install */
    private boolean installWithClasspath(String classpath) {
        String javaBin = Paths.get(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        return runProcess(new ProcessBuilder(
                javaBin, "-cp", classpath,
                "com.microsoft.playwright.CLI", "install", "chromium"),
                Paths.get(System.getProperty("user.dir")));
    }

    /** fat jar 模式：借助 Maven 执行 CLI install（工程目录需存在 pom.xml） */
    private boolean installWithMaven() {
        Path userDir = Paths.get(System.getProperty("user.dir"));
        Path pomDir = Files.exists(userDir.resolve("pom.xml"))
                ? userDir
                : userDir.resolve("server");
        if (!Files.exists(pomDir.resolve("pom.xml"))) {
            log.warn("未找到 pom.xml，无法自动安装浏览器，请运行 install-browser.bat");
            return false;
        }
        String mvn = isWindows() ? "mvn.cmd" : "mvn";
        // 注意：Playwright CLI 内部会 System.exit，放在子进程中执行不影响后端服务
        return runProcess(new ProcessBuilder(
                mvn, "-B", "exec:java", "-Dexec.args=install chromium"), pomDir);
    }

    /** 执行子进程并等待，安装成功以浏览器目录实际存在为准 */
    private boolean runProcess(ProcessBuilder pb, Path workDir) {
        pb.directory(workDir.toFile());
        pb.inheritIO();
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(properties.getInstallTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("浏览器下载超时");
                return false;
            }
            return isInstalled();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("启动浏览器安装进程失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Playwright 浏览器缓存目录：
     * 优先环境变量 PLAYWRIGHT_BROWSERS_PATH，否则用系统默认缓存位置
     */
    private Path browsersDir() {
        String env = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        if (env != null && !env.isBlank()) {
            return Paths.get(env);
        }
        if (isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            Path base = (localAppData != null && !localAppData.isBlank())
                    ? Paths.get(localAppData)
                    : Paths.get(System.getProperty("user.home"), "AppData", "Local");
            return base.resolve("ms-playwright");
        }
        if (isMac()) {
            return Paths.get(System.getProperty("user.home"), "Library", "Caches", "ms-playwright");
        }
        return Paths.get(System.getProperty("user.home"), ".cache", "ms-playwright");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /** 供状态接口展示的浏览器缓存路径 */
    public String browsersPath() {
        return browsersDir().toString();
    }

    /** 供状态接口展示的UA数量等扩展信息预留 */
    public List<String> supportedPlatforms() {
        return List.of("win", "mac", "linux");
    }
}
