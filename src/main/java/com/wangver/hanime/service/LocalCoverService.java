package com.wangver.hanime.service;

import com.wangver.hanime.model.DownloadTaskView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Service
public class LocalCoverService {

    private final Path cacheDir;
    private final HanimeParserService parserService;
    private final DownloadHistoryStore historyStore;
    private final HttpClient httpClient;

    @Autowired
    public LocalCoverService(HanimeParserService parserService, DownloadHistoryStore historyStore) {
        this(
                Path.of(System.getProperty("user.dir"), "cache", "covers"),
                parserService,
                historyStore
        );
    }

    LocalCoverService(Path cacheDir, HanimeParserService parserService, DownloadHistoryStore historyStore) {
        this.cacheDir = cacheDir;
        this.parserService = parserService;
        this.historyStore = historyStore;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void save(String taskId, String imageUrl) {
        if (taskId == null || taskId.isBlank()) return;
        if (imageUrl == null || imageUrl.isBlank()) return;

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://hanime1.me/")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200 && response.body() != null && response.body().length > 0) {
                Files.createDirectories(cacheDir);
                Files.write(cacheDir.resolve(taskId), response.body());
            }
        } catch (Exception e) {
            System.out.println("本地封面缓存失败: " + taskId + " -> " + e.getMessage());
        }
    }

    public Path getCoverPath(String taskId) {
        if (taskId == null || taskId.isBlank()) return null;
        Path file = cacheDir.resolve(taskId);
        return Files.exists(file) && Files.isRegularFile(file) ? file : null;
    }

    public byte[] getOrFetch(String taskId, String pageUrl, String fallbackImageUrl) throws Exception {
        Path cached = getCoverPath(taskId);
        if (cached != null) {
            return Files.readAllBytes(cached);
        }

        if (pageUrl == null || pageUrl.isBlank()) {
            List<DownloadTaskView> history = historyStore.load();
            for (DownloadTaskView h : history) {
                if (h.getId().equals(taskId)) {
                    pageUrl = h.getPageUrl();
                    if (fallbackImageUrl == null || fallbackImageUrl.isBlank()) {
                        fallbackImageUrl = h.getThumbnail();
                    }
                    break;
                }
            }
        }

        String thumbnailUrl = null;
        if (pageUrl != null && !pageUrl.isBlank()) {
            try {
                thumbnailUrl = parserService.fetchThumbnail(pageUrl);
            } catch (Exception e) {
                System.out.println("从页面提取封面失败: " + taskId + " -> " + e.getMessage());
            }
        }

        if ((thumbnailUrl == null || thumbnailUrl.isBlank()) && fallbackImageUrl != null && !fallbackImageUrl.isBlank()) {
            thumbnailUrl = fallbackImageUrl;
        }

        if (thumbnailUrl == null || thumbnailUrl.isBlank()) {
            throw new IOException("未找到封面地址");
        }

        save(taskId, thumbnailUrl);
        cached = getCoverPath(taskId);
        if (cached != null) {
            return Files.readAllBytes(cached);
        }
        throw new IOException("封面下载后仍然无法读取");
    }
}
