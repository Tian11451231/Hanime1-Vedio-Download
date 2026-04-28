package com.wangver.hanime.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

@Service
public class HistoryCoverService {

    private static final String DEFAULT_CONTENT_TYPE = "image/jpeg";

    private final Path cacheDir;
    private final VideoPageThumbnailResolver thumbnailResolver;
    private final CoverImageFetcher imageFetcher;

    @Autowired
    public HistoryCoverService(HanimeParserService parserService, ImageProxyService imageProxyService) {
        this(
                Path.of(System.getProperty("user.dir")).resolve("history-cover-cache"),
                parserService::fetchThumbnail,
                imageProxyService::fetchImage
        );
    }

    HistoryCoverService(
            Path cacheDir,
            VideoPageThumbnailResolver thumbnailResolver,
            CoverImageFetcher imageFetcher
    ) {
        this.cacheDir = cacheDir;
        this.thumbnailResolver = thumbnailResolver;
        this.imageFetcher = imageFetcher;
    }

    public ImageProxyService.ImageResponse fetchCover(String pageUrl) throws Exception {
        return fetchCover(pageUrl, null);
    }

    public synchronized ImageProxyService.ImageResponse fetchCover(String pageUrl, String thumbnailUrl) throws Exception {
        if (pageUrl == null || pageUrl.isBlank()) {
            throw new IllegalArgumentException("缺少视频页面地址");
        }

        String key = hash(cacheKey(pageUrl));
        Path imagePath = imagePath(key);
        Path metaPath = metaPath(key);
        ImageProxyService.ImageResponse cached = readCached(imagePath, metaPath);
        if (cached != null) {
            return cached;
        }

        if (thumbnailUrl == null || thumbnailUrl.isBlank()) {
            thumbnailUrl = thumbnailResolver.resolveThumbnail(pageUrl);
        }
        if (thumbnailUrl == null || thumbnailUrl.isBlank()) {
            throw new IOException("未找到历史记录封面");
        }

        String resolvedUrl = thumbnailUrl.startsWith("http://") || thumbnailUrl.startsWith("https://")
                ? thumbnailUrl
                : resolveImageUrl(pageUrl, thumbnailUrl);
        ImageProxyService.ImageResponse image = imageFetcher.fetchImage(resolvedUrl);
        if (image.bytes() == null || image.bytes().length == 0) {
            throw new IOException("历史记录封面下载失败");
        }
        writeCached(imagePath, metaPath, image, thumbnailUrl);
        return image;
    }

    private ImageProxyService.ImageResponse readCached(Path imagePath, Path metaPath) throws IOException {
        if (!Files.exists(imagePath) || !Files.exists(metaPath)) {
            return null;
        }

        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(metaPath)) {
            properties.load(inputStream);
        }
        String contentType = properties.getProperty("contentType", DEFAULT_CONTENT_TYPE);
        return new ImageProxyService.ImageResponse(Files.readAllBytes(imagePath), contentType);
    }

    private void writeCached(
            Path imagePath,
            Path metaPath,
            ImageProxyService.ImageResponse image,
            String thumbnailUrl
    ) throws IOException {
        Files.createDirectories(imagePath.getParent());
        Files.createDirectories(metaPath.getParent());
        Files.write(imagePath, image.bytes());

        Properties properties = new Properties();
        properties.setProperty("contentType", hasText(image.contentType()) ? image.contentType() : DEFAULT_CONTENT_TYPE);
        properties.setProperty("thumbnailUrl", thumbnailUrl);
        try (OutputStream outputStream = Files.newOutputStream(metaPath)) {
            properties.store(outputStream, "Hanime history cover cache");
        }
    }

    private Path imagePath(String key) {
        return cacheDir.resolve("images").resolve(key + ".bin");
    }

    private Path metaPath(String key) {
        return cacheDir.resolve("metadata").resolve(key + ".properties");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String resolveImageUrl(String pageUrl, String thumbnailUrl) {
        return URI.create(pageUrl).resolve(thumbnailUrl).toString();
    }

    private String cacheKey(String pageUrl) {
        try {
            URI uri = URI.create(pageUrl);
            String query = uri.getRawQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    int equalsIndex = pair.indexOf('=');
                    String name = equalsIndex >= 0 ? pair.substring(0, equalsIndex) : pair;
                    String value = equalsIndex >= 0 ? pair.substring(equalsIndex + 1) : "";
                    if ("v".equals(name) && !value.isBlank()) {
                        return "hanime-watch-" + value;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return pageUrl;
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @FunctionalInterface
    interface VideoPageThumbnailResolver {
        String resolveThumbnail(String pageUrl) throws Exception;
    }

    @FunctionalInterface
    interface CoverImageFetcher {
        ImageProxyService.ImageResponse fetchImage(String imageUrl) throws Exception;
    }
}
