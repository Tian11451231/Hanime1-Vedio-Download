package com.wangver.hanime.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class HanimeParserService {

    @Autowired
    private PlaywrightBrowserService browserService;

    public Map<String, Object> parse(String url) throws Exception {
        return browserService.runSerialized(() -> {
            String videoId = extractVideoId(url);

            System.out.println("Starting Parse URL: " + url + " (Persistent Mode)");

            Map<String, Object> result = new HashMap<>();
            boolean newlyVerified = false;
            for (int attempt = 1; attempt <= 2; attempt++) {
                Page page = browserService.createPage();
                try {

                    page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(60000));

                    boolean verificationRetryNeeded = false;
                    try {
                        page.waitForSelector(".video-details-wrapper, #player, .title, h1.title, h3.title", new Page.WaitForSelectorOptions().setTimeout(20000));
                    } catch (Exception e) {
                        System.out.println("Wait timeout (20s). checking if stuck in background mode...");
                        if (browserService.hasVerifiedSession()) {
                            verificationRetryNeeded = true;
                        } else {
                            System.out.println("Proceeding with current content (might be blocked by CF)...");
                        }
                    }

                    if (verificationRetryNeeded) {
                        System.out.println("Likely encountered CF in background mode. Restarting headful and retrying parse...");
                        closePageQuietly(page);
                        page = null;
                        browserService.forceRestartHeadful();
                        continue;
                    }

                    String html = page.content();

                    String downloadHtml = fetchDownloadPageHtml(page, videoId);

                    String bestStream = selectBestDownloadSource(html, downloadHtml);

                    result.put("videoUrl", bestStream != null ? bestStream.replace("&amp;", "&") : "");

                    Document doc = Jsoup.parse(html);
                    Element titleEl = doc.selectFirst("h3.title, h1.title, .title, .video-title");
                    if (titleEl != null) {
                        result.put("title", TitleTextNormalizer.normalize(titleEl.text()));
                    } else {
                        Element ogTitle = doc.selectFirst("meta[property=og:title], meta[name=title]");
                        String fallbackTitle = ogTitle != null ? ogTitle.attr("content") : doc.title();
                        result.put("title", TitleTextNormalizer.normalize(fallbackTitle));
                    }

                    Element thumbMeta = doc.selectFirst("meta[itemprop=thumbnailUrl]");
                    if (thumbMeta != null) {
                        result.put("thumbnail", thumbMeta.attr("content"));
                    } else {
                        Element ogImg = doc.selectFirst("meta[property=og:image]");
                        if (ogImg != null) {
                            result.put("thumbnail", ogImg.attr("content"));
                        } else {
                            Element videoEl = doc.selectFirst("video#player");
                            if (videoEl != null && videoEl.hasAttr("poster")) {
                                result.put("thumbnail", videoEl.attr("poster"));
                            } else {
                                result.put("thumbnail", "https://via.placeholder.com/1280x720.png?text=No+Cover");
                            }
                        }
                    }

                    result.put("playlist", extractPlaylist(html, url));
                    result.put("relatedVideos", extractRelatedVideos(html, url));

                    if (bestStream != null && !bestStream.trim().isEmpty()) {
                        newlyVerified = browserService.markAsVerified();
                    }
                    break;
                } finally {
                    closePageQuietly(page);
                }
            }

            browserService.restartIfNewlyVerified(newlyVerified);
            if (!result.isEmpty()) {
                return result;
            }

            throw new IllegalStateException("解析失败，浏览器验证后仍无法获取页面");
        });
    }

    private String extractHighestQualityStream(String html) {
        Document doc = Jsoup.parse(html);

        String downloadTableUrl = extractHighestQualityDownloadUrl(doc);
        if (downloadTableUrl != null) {
            return downloadTableUrl;
        }
        
        List<String> mp4Links = extractLinksByRegex(html, "(https?://[^\\s'\"]+\\.mp4[^\\s'\"]*)");
        String preferredMp4 = selectHighestQualityLink(mp4Links);
        if (preferredMp4 != null) {
            return preferredMp4;
        }

        // 尝试从 <source> 标签找
        Elements sources = doc.select("source[src]");
        for (Element s : sources) {
            String src = s.attr("src");
            if (src.contains(".m3u8") || src.contains(".mp4")) return src;
        }

        // 回退正则匹配
        List<String> m3u8Links = extractLinksByRegex(html, "(https?://[^\\s'\"]+\\.m3u8[^\\s'\"]*)");

        if (!m3u8Links.isEmpty()) {
            return m3u8Links.get(0);
        }
        
        return null; // 未找到
    }

    private String selectBestDownloadSource(String pageHtml, String downloadHtml) {
        String bestDownloadUrl = null;
        if (downloadHtml != null && !downloadHtml.isBlank()) {
            bestDownloadUrl = extractHighestQualityStream(downloadHtml);
        }
        if (bestDownloadUrl != null && !bestDownloadUrl.isBlank()) {
            return bestDownloadUrl;
        }
        return extractHighestQualityStream(pageHtml);
    }

    private String fetchDownloadPageHtml(Page page, String videoId) {
        if (videoId == null || videoId.isBlank()) {
            return null;
        }

        System.out.println("优先尝试进入下载源池页面抓取最高画质直链...");
        List<String> candidates = List.of(
                "https://hanime1.me/download?v=" + videoId,
                "https://javchu.com/download?v=" + videoId
        );

        for (String candidate : candidates) {
            try {
                page.navigate(candidate);
                page.waitForSelector("table.download-table a[data-url], a[data-url]", new Page.WaitForSelectorOptions().setTimeout(10000));
                return page.content();
            } catch (Exception ex) {
                System.out.println("下载页没有找到可用直链: " + candidate + " -> " + ex.getMessage());
            }
        }

        return null;
    }

    private String extractHighestQualityDownloadUrl(Document doc) {
        Elements rows = doc.select("table.download-table tr");
        String bestUrl = null;
        int bestScore = -1;

        for (Element row : rows) {
            Element qualityCell = row.selectFirst("td:nth-of-type(2)");
            Element downloadButton = row.selectFirst("a[data-url]");
            if (qualityCell == null || downloadButton == null) {
                continue;
            }

            String candidateUrl = downloadButton.attr("data-url").replace("&amp;", "&");
            if (candidateUrl.isBlank() || candidateUrl.toLowerCase().contains("juicyads")) {
                continue;
            }

            int score = qualityScore(qualityCell.text());
            if (score > bestScore) {
                bestScore = score;
                bestUrl = candidateUrl;
            }
        }

        return bestUrl;
    }

    private int qualityScore(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (normalized.contains("2160") || normalized.contains("4k")) {
            return 2160;
        }
        if (normalized.contains("1440")) {
            return 1440;
        }
        if (normalized.contains("1080")) {
            return 1080;
        }
        if (normalized.contains("720")) {
            return 720;
        }
        if (normalized.contains("480")) {
            return 480;
        }
        return 0;
    }

    private String selectHighestQualityLink(List<String> links) {
        String bestLink = null;
        int bestScore = -1;
        for (String link : links) {
            int score = qualityScore(link);
            if (score > bestScore) {
                bestScore = score;
                bestLink = link;
            }
        }
        return bestLink;
    }
    
    private List<String> extractLinksByRegex(String html, String regexStr) {
        List<String> links = new ArrayList<>();
        Pattern pattern = Pattern.compile(regexStr, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            String link = matcher.group(1);
            // 过滤广告
            if (!link.toLowerCase().contains("juicyads")) {
                links.add(link);
            }
        }
        return links;
    }

    private String extractVideoId(String url) {
        try {
            return url.split("v=")[1].split("&")[0];
        } catch (Exception e) {
            return "";
        }
    }

    private List<Map<String, String>> extractPlaylist(String html, String currentUrl) {
        Document doc = Jsoup.parse(html);
        return extractVideoCards(doc.select("#video-playlist-wrapper .related-watch-wrap, #video-playlist-wrapper .multiple-link-wrapper, .related-watch-wrap.multiple-link-wrapper"), currentUrl);
    }

    private List<Map<String, String>> extractRelatedVideos(String html, String currentUrl) {
        Document doc = Jsoup.parse(html);
        List<Map<String, String>> relatedVideos = new ArrayList<>();

        Elements relatedTabCards = doc.select("#related-tabcontent > div.row a[href*='watch?v='], #related-tabcontent div.row a[href*='watch?v=']");
        relatedVideos.addAll(extractVideoCards(relatedTabCards, currentUrl));
        if (!relatedVideos.isEmpty()) {
            return relatedVideos;
        }

        Elements sectionRoots = doc.select("section, .home-rows-videos-wrapper, .home-rows-videos, .video-section, .content-section, .container");
        for (Element section : sectionRoots) {
            if (!looksLikeRelatedSection(section) || shouldExcludeSection(section)) {
                continue;
            }
            relatedVideos.addAll(extractVideoCards(section.select("a[href*='watch?v=']"), currentUrl));
            if (!relatedVideos.isEmpty()) {
                break;
            }
        }

        if (!relatedVideos.isEmpty()) {
            return relatedVideos;
        }

        Elements fallbackCards = doc.select(".home-rows-videos-wrapper a[href*='watch?v='], .home-rows-videos a[href*='watch?v=']");
        return extractVideoCards(fallbackCards, currentUrl);
    }

    private Map<String, String> buildPlaylistItem(String link, String thumbnail, String title) {
        Map<String, String> item = new HashMap<>();
        item.put("url", link);
        item.put("thumbnail", thumbnail);
        item.put("title", title);
        return item;
    }

    private String extractImageUrl(Element imgEl) {
        if (imgEl == null) {
            return "";
        }
        if (imgEl.hasAttr("data-src") && !imgEl.attr("data-src").isBlank()) {
            return imgEl.attr("data-src");
        }
        return imgEl.attr("src");
    }

    private Element selectBestPlaylistImage(Elements images) {
        Element fallback = null;
        for (Element image : images) {
            String imageUrl = extractImageUrl(image);
            if (imageUrl.isBlank()) {
                continue;
            }
            if (fallback == null) {
                fallback = image;
            }
            String normalized = imageUrl.toLowerCase(Locale.ROOT);
            if (normalized.contains("/image/thumbnail/") || normalized.contains("thumbnail")) {
                return image;
            }
            if (image.hasAttr("alt") && !image.attr("alt").isBlank()
                    && !normalized.contains("card_doujin_background")
                    && !normalized.contains("/image/icon/")) {
                return image;
            }
        }

        for (Element image : images) {
            String imageUrl = extractImageUrl(image);
            if (imageUrl.isBlank()) {
                continue;
            }
            String normalized = imageUrl.toLowerCase(Locale.ROOT);
            if (!normalized.contains("card_doujin_background") && !normalized.contains("/image/icon/")) {
                return image;
            }
        }
        return fallback;
    }

    private List<Map<String, String>> extractVideoCards(Elements cardElements, String currentUrl) {
        List<Map<String, String>> items = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();

        for (Element card : cardElements) {
            Element linkEl = "a".equals(card.tagName()) ? card : card.selectFirst("a[href*='watch?v=']");
            if (linkEl == null) {
                continue;
            }

            String link = absolutizeWatchUrl(linkEl.attr("href"), currentUrl);
            if (link == null || seenUrls.contains(link)) {
                continue;
            }

            Element scope = "a".equals(card.tagName()) ? card : linkEl;
            Element titleEl = card.selectFirst(".home-rows-videos-title, .card-mobile-title, .video-title, .title, [title]");
            if (titleEl == null) {
                titleEl = scope.selectFirst(".home-rows-videos-title, .card-mobile-title, .video-title, .title, [title]");
            }
            Element imgEl = selectBestPlaylistImage(card.select("img[data-src], img[src]"));
            if (imgEl == null) {
                imgEl = selectBestPlaylistImage(scope.select("img[data-src], img[src]"));
            }

            String title = extractCardTitle(titleEl, scope);
            String thumbnail = extractImageUrl(imgEl);
            if (title.isBlank() || thumbnail.isBlank()) {
                continue;
            }

            items.add(buildPlaylistItem(link, thumbnail, title));
            seenUrls.add(link);
        }

        return items;
    }

    private String extractCardTitle(Element titleEl, Element fallbackScope) {
        if (titleEl != null) {
            if (titleEl.hasAttr("title") && !titleEl.attr("title").isBlank()) {
                return TitleTextNormalizer.normalize(titleEl.attr("title"));
            }
            String text = titleEl.text();
            if (!text.isBlank()) {
                return TitleTextNormalizer.normalize(text);
            }
        }

        if (fallbackScope != null && fallbackScope.hasAttr("title") && !fallbackScope.attr("title").isBlank()) {
            return TitleTextNormalizer.normalize(fallbackScope.attr("title"));
        }
        return "";
    }

    private boolean looksLikeRelatedSection(Element section) {
        String heading = section.select("h1, h2, h3, h4, .section-title, .home-rows-title").text();
        String normalized = heading == null ? "" : heading.replaceAll("\\s+", "");
        return normalized.contains("相关") || normalized.contains("相關") || normalized.contains("推荐") || normalized.contains("推薦");
    }

    private boolean shouldExcludeSection(Element section) {
        String heading = section.select("h1, h2, h3, h4, .section-title, .home-rows-title").text();
        String normalized = heading == null ? "" : heading.replaceAll("\\s+", "");
        return normalized.contains("评论") || normalized.contains("評論") || normalized.contains("新番资讯") || normalized.contains("新番資訊");
    }

    private String absolutizeWatchUrl(String href, String currentUrl) {
        if (href == null || href.isBlank() || !href.contains("watch?v=")) {
            return null;
        }
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        try {
            return java.net.URI.create(currentUrl).resolve(href).toString();
        } catch (Exception exception) {
            if (href.startsWith("/")) {
                return "https://hanime1.me" + href;
            }
            return null;
        }
    }

    private void closePageQuietly(Page page) {
        if (page == null) {
            return;
        }
        try {
            page.close();
        } catch (PlaywrightException ignored) {
        }
    }
}
