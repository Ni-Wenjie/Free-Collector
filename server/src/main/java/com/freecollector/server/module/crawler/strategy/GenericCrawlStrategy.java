package com.freecollector.server.module.crawler.strategy;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用爬取策略：所有网站都能用的基础实现
 *
 * 能力：
 *  - 链接发现：扫描所有 <a> 标签，提取 href + 文本
 *  - 媒体发现：扫描 <img>/<video>/<audio>/<source> 标签，以及 data-* 懒加载属性
 *  - JS嵌入URL：从 script 块中正则提取 m3u8/mp4/mp3/pdf 等直链
 *  - CSS背景图：提取 background-image 中的 url()
 *
 * 其他策略（VideoSiteStrategy 等）可以继承此类，覆盖特定方法。
 */
@Slf4j
@Component
public class GenericCrawlStrategy implements CrawlStrategy {

    /** JS 中常见的视频/媒体 URL 正则 */
    private static final Pattern MEDIA_URL_PATTERN = Pattern.compile(
            "(?:https?://|/)[^\"'\\s<>)]+?\\.(m3u8|mp4|mp3|webm|wav|flac|pdf|doc|docx|xls|xlsx|ppt|pptx|zip|rar)",
            Pattern.CASE_INSENSITIVE
    );

    /** data-* 懒加载属性名（常见的图片懒加载属性） */
    private static final Set<String> LAZY_IMAGE_ATTRS = Set.of(
            "data-src", "data-original", "data-lazy-src", "data-image",
            "data-actualsrc", "data-url", "data-thumb", "data-thumbnail"
    );

    @Override
    public SiteType getSiteType() {
        return SiteType.GENERIC;
    }

    @Override
    public List<LinkCandidate> discoverLinks(Page page, int depth) {
        List<LinkCandidate> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try {
            // 扫描所有 <a> 标签
            List<ElementHandle> anchors = page.querySelectorAll("a[href]");
            String baseUrl = page.url();

            for (ElementHandle anchor : anchors) {
                try {
                    String href = anchor.getAttribute("href");
                    if (href == null || href.isBlank()) continue;

                    // 跳过锚点、JS、邮件、电话等非HTTP链接
                    if (!isHttpUrl(href) && !isRelativePath(href)) continue;
                    if (href.startsWith("#") || href.startsWith("javascript:")
                            || href.startsWith("mailto:") || href.startsWith("tel:")) continue;

                    // 转换为绝对URL
                    String absoluteUrl = resolveUrl(baseUrl, href);
                    if (absoluteUrl == null) continue;

                    // URL 去重
                    if (!seen.add(normalizeUrl(absoluteUrl))) continue;

                    // 获取链接文本
                    String text = anchor.innerText();
                    if (text != null) text = text.trim();
                    if (text != null && text.length() > 100) text = text.substring(0, 100) + "...";

                    result.add(new LinkCandidate(absoluteUrl, text));
                } catch (Exception e) {
                    // 单个链接提取失败不影响其他
                }
            }
            log.debug("发现 {} 个链接（depth={}）", result.size(), depth);
        } catch (Exception e) {
            log.warn("链接发现失败: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public List<MediaCandidate> discoverMedia(Page page) {
        List<MediaCandidate> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String baseUrl = page.url();

        try {
            collectFromImgTags(page, baseUrl, result, seen);
            collectFromVideoTags(page, baseUrl, result, seen);
            collectFromAudioTags(page, baseUrl, result, seen);
            collectFromScriptBlocks(page, result, seen);
            collectFromCssBackground(page, baseUrl, result, seen);

            log.debug("发现 {} 个媒体资源", result.size());
        } catch (Exception e) {
            log.warn("媒体发现失败: {}", e.getMessage());
        }
        return result;
    }

    private void collectFromImgTags(Page page, String baseUrl,
                                    List<MediaCandidate> result, Set<String> seen) {
        try {
            List<ElementHandle> imgs = page.querySelectorAll("img");
            for (ElementHandle img : imgs) {
                try {
                    // 优先取懒加载属性
                    String src = null;
                    for (String attr : LAZY_IMAGE_ATTRS) {
                        src = img.getAttribute(attr);
                        if (src != null && !src.isBlank() && !src.equals("none")) break;
                    }
                    // 回退到 src / currentSrc
                    if (src == null || src.isBlank()) {
                        src = img.getAttribute("src");
                    }
                    if (src == null || src.isBlank()) {
                        src = img.getAttribute("currentSrc");
                    }
                    if (src == null || src.isBlank()) continue;

                    String absoluteUrl = resolveUrl(baseUrl, src);
                    if (absoluteUrl != null && seen.add(absoluteUrl)) {
                        result.add(new MediaCandidate(absoluteUrl, "IMAGE", "<img>"));
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.debug("img 标签扫描失败: {}", e.getMessage());
        }
    }

    private void collectFromVideoTags(Page page, String baseUrl,
                                      List<MediaCandidate> result, Set<String> seen) {
        try {
            // <video> 标签的 src 属性
            List<ElementHandle> videos = page.querySelectorAll("video[src]");
            for (ElementHandle v : videos) {
                try {
                    String src = v.getAttribute("src");
                    if (src != null && !src.isBlank()) {
                        String absoluteUrl = resolveUrl(baseUrl, src);
                        if (absoluteUrl != null && seen.add(absoluteUrl)) {
                            result.add(new MediaCandidate(absoluteUrl, "VIDEO", "<video>"));
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            // <video> 内的 <source> 标签
            List<ElementHandle> sources = page.querySelectorAll("video source[src]");
            for (ElementHandle s : sources) {
                try {
                    String src = s.getAttribute("src");
                    if (src != null && !src.isBlank()) {
                        String absoluteUrl = resolveUrl(baseUrl, src);
                        if (absoluteUrl != null && seen.add(absoluteUrl)) {
                            result.add(new MediaCandidate(absoluteUrl, "VIDEO", "<source>"));
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.debug("video 标签扫描失败: {}", e.getMessage());
        }
    }

    private void collectFromAudioTags(Page page, String baseUrl,
                                      List<MediaCandidate> result, Set<String> seen) {
        try {
            List<ElementHandle> audios = page.querySelectorAll("audio[src], audio source[src]");
            for (ElementHandle a : audios) {
                try {
                    String src = a.getAttribute("src");
                    if (src != null && !src.isBlank()) {
                        String absoluteUrl = resolveUrl(baseUrl, src);
                        if (absoluteUrl != null && seen.add(absoluteUrl)) {
                            result.add(new MediaCandidate(absoluteUrl, "AUDIO", "<audio>"));
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.debug("audio 标签扫描失败: {}", e.getMessage());
        }
    }

    private void collectFromScriptBlocks(Page page,
                                         List<MediaCandidate> result, Set<String> seen) {
        try {
            // 取全页面 HTML 后正则匹配媒体URL
            String html = page.content();
            Matcher matcher = MEDIA_URL_PATTERN.matcher(html);
            while (matcher.find()) {
                String url = matcher.group(0);
                if (seen.add(url)) {
                    String type = inferMediaType(url);
                    result.add(new MediaCandidate(url, type, "<script>"));
                }
            }
        } catch (Exception e) {
            log.debug("script 块扫描失败: {}", e.getMessage());
        }
    }

    private void collectFromCssBackground(Page page, String baseUrl,
                                          List<MediaCandidate> result, Set<String> seen) {
        try {
            // 通过 JS 收集所有带 background-image 的元素
            String js = "Array.from(document.querySelectorAll('*')).map(el => {" +
                    "const bg = getComputedStyle(el).backgroundImage;" +
                    "if (bg && bg !== 'none') { const m = bg.match(/url\\(['\"]?(.*?)['\"]?\\)/);" +
                    "return m ? m[1] : null; } return null;" +
                    "}).filter(Boolean)";
            Object evalResult = page.evaluate(js);
            if (evalResult instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> rawUrls = (List<Object>) evalResult;
                for (Object raw : rawUrls) {
                    if (raw == null) continue;
                    String url = raw.toString();
                    String absoluteUrl = resolveUrl(baseUrl, url);
                    if (absoluteUrl != null && seen.add(absoluteUrl)) {
                        result.add(new MediaCandidate(absoluteUrl, "IMAGE", "background-image"));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("CSS background 扫描失败: {}", e.getMessage());
        }
    }

    /** 推断媒体类型（从URL扩展名） */
    public static String inferMediaType(String url) {
        String lower = url.toLowerCase();
        if (lower.endsWith(".m3u8") || lower.endsWith(".mp4") || lower.endsWith(".webm")
                || lower.endsWith(".mov") || lower.endsWith(".avi") || lower.endsWith(".mkv")) {
            return "VIDEO";
        }
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac")
                || lower.endsWith(".ogg") || lower.endsWith(".aac")) {
            return "AUDIO";
        }
        if (lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".ppt")
                || lower.endsWith(".pptx") || lower.endsWith(".txt") || lower.endsWith(".csv")) {
            return "DOCUMENT";
        }
        if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")
                || lower.endsWith(".tar") || lower.endsWith(".gz")) {
            return "DOCUMENT";
        }
        return "OTHER";
    }

    /** 判断是否 HTTP/HTTPS URL */
    public static boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /** 判断是否相对路径 */
    public static boolean isRelativePath(String url) {
        return url != null && (url.startsWith("/") || url.startsWith("./") || url.startsWith("../"));
    }

    /** 将相对URL解析为绝对URL */
    public static String resolveUrl(String baseUrl, String relativeOrAbsolute) {
        if (relativeOrAbsolute == null || relativeOrAbsolute.isBlank()) return null;
        if (isHttpUrl(relativeOrAbsolute)) return relativeOrAbsolute;
        if (relativeOrAbsolute.startsWith("//")) return "https:" + relativeOrAbsolute;

        try {
            URI base = URI.create(baseUrl);
            URI resolved = base.resolve(relativeOrAbsolute);
            String result = resolved.toString();
            return result;
        } catch (Exception e) {
            log.debug("URL 解析失败 base={} rel={}", baseUrl, relativeOrAbsolute);
            return null;
        }
    }

    /** URL 标准化（去 fragment、统一末尾斜杠）用于去重 */
    public static String normalizeUrl(String url) {
        if (url == null) return "";
        int hashIdx = url.indexOf('#');
        String noHash = hashIdx >= 0 ? url.substring(0, hashIdx) : url;
        // 去末尾斜杠（但保留根路径 /）
        if (noHash.length() > 1 && noHash.endsWith("/")) {
            noHash = noHash.substring(0, noHash.length() - 1);
        }
        return noHash.toLowerCase();
    }
}
