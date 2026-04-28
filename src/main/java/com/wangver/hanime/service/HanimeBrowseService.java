package com.wangver.hanime.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class HanimeBrowseService {

    @Autowired
    private PlaywrightBrowserService browserService;

    private static final Map<String, String> CATEGORY_GENRE_MAP = createCategoryGenreMap();

    public Map<String, Object> fetchCategory(String category, int pageNum) throws Exception {
        return browserService.runSerialized(() -> {
            String url = buildCategoryUrl(category, pageNum);
            System.out.println("Browse: " + category + " Page: " + pageNum + " -> URL: " + url);

            Page page = browserService.createPage();
            try {
                page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(60000));
                page.waitForSelector("a[href*='watch?v=']",
                        new Page.WaitForSelectorOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED).setTimeout(20000));
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);

                String html = page.content();
                return buildCategoryResult(html, pageNum);
            } finally {
                closePageQuietly(page);
            }
        });
    }

    private Map<String, Object> buildCategoryResult(String html, int pageNum) {
        Map<String, Object> result = new HashMap<>();
        result.put("videos", parseVideoGrid(html));
        result.put("currentPage", pageNum);
        result.put("totalPages", parseTotalPages(html, pageNum));
        return result;
    }

    private String buildCategoryUrl(String category, int pageNum) {
        String genre = CATEGORY_GENRE_MAP.getOrDefault(category, category);
        String encodedCat = URLEncoder.encode(genre, StandardCharsets.UTF_8);
        return "https://hanime1.me/search?genre=" + encodedCat + "&page=" + pageNum;
    }

    private static Map<String, String> createCategoryGenreMap() {
        Map<String, String> categories = new LinkedHashMap<>();
        categories.put("裏番", "裏番");
        categories.put("泡麵番", "泡麵番");
        categories.put("Motion Anime", "Motion Anime");
        categories.put("3DCG", "3DCG");
        categories.put("2.5D", "2.5D");
        categories.put("2D動畫", "2D動畫");
        categories.put("2D动画", "2D動畫");
        categories.put("AI生成", "AI生成");
        categories.put("MMD", "MMD");
        categories.put("Cosplay", "Cosplay");
        return Collections.unmodifiableMap(categories);
    }

    private void closePageQuietly(Page page) {
        if (page == null) return;
        try {
            page.close();
        } catch (PlaywrightException ignored) {}
    }

    private int parseTotalPages(String html, int defaultPage) {
        Document doc = Jsoup.parse(html);
        Elements pageLinks = doc.select(".pagination a, ul.pagination li a");
        int maxPage = defaultPage;
        for (Element a : pageLinks) {
            try {
                int p = Integer.parseInt(a.text().trim());
                if (p > maxPage) maxPage = p;
            } catch (NumberFormatException e) {
                String href = a.attr("href");
                if (href != null && href.contains("page=")) {
                    try {
                        String pageStr = href.substring(href.indexOf("page=") + 5);
                        int ampIndex = pageStr.indexOf("&");
                        if (ampIndex > 0) pageStr = pageStr.substring(0, ampIndex);
                        int p = Integer.parseInt(pageStr);
                        if (p > maxPage) maxPage = p;
                    } catch (Exception ignored) {}
                }
            }
        }
        return Math.max(maxPage, 1);
    }

    private List<Map<String, String>> parseVideoGrid(String html) {
        List<Map<String, String>> videos = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        Elements cards = doc.select("a[href*=\"watch?v=\"]");
        Set<String> seen = new HashSet<>();

        for (Element a : cards) {
            String link = a.attr("href");
            if (link.startsWith("/")) {
                link = "https://hanime1.me" + link;
            }
            if (seen.contains(link)) continue;

            Element img = a.selectFirst("img");
            Element titleDiv = a.selectFirst(".home-rows-videos-title, .video-title, .card-mobile-title, .search-result-title, .title");
            Element titleContainer = a.closest("[title]");

            if (img != null && titleDiv != null) {
                Map<String, String> item = new HashMap<>();
                String thumbUrl = img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src");
                item.put("thumbnail", thumbUrl);
                item.put("url", link);
                String visibleTitle = titleDiv.text();
                String rawTitle = visibleTitle;
                if (TitleTextNormalizer.looksBroken(visibleTitle)
                        && titleContainer != null
                        && titleContainer.hasAttr("title")) {
                    rawTitle = titleContainer.attr("title");
                }
                item.put("title", TitleTextNormalizer.normalize(rawTitle));
                videos.add(item);
                seen.add(link);
            }
        }
        return videos;
    }
}
