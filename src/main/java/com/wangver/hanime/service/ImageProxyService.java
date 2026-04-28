package com.wangver.hanime.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ImageProxyService {

    private static final int MAX_CACHE_ENTRIES = 300;
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final byte[] PLACEHOLDER = new byte[0];

    private final HanimeHttpSessionService httpSessionService;
    private final Clock clock;
    private final Map<String, CachedImage> cache = new LinkedHashMap<>(16, 0.75f, true);

    @Autowired
    public ImageProxyService(HanimeHttpSessionService httpSessionService) {
        this(httpSessionService, Clock.systemUTC());
    }

    ImageProxyService(HanimeHttpSessionService httpSessionService, Clock clock) {
        this.httpSessionService = httpSessionService;
        this.clock = clock;
    }

    public ImageResponse fetchImage(String url) throws IOException, InterruptedException {
        CachedImage cached = getCached(url);
        if (cached != null) {
            return new ImageResponse(cached.bytes(), cached.contentType());
        }

        try {
            HttpResponse<byte[]> response = httpSessionService.fetchBytes(url, "https://hanime1.me/");
            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("image/jpeg");
            ImageResponse imageResponse = new ImageResponse(response.body(), contentType);
            putCached(url, imageResponse);
            return imageResponse;
        } catch (Exception e) {
            System.out.println("图片获取失败，使用占位图: " + url + " -> " + e.getMessage());
            putCached(url, new ImageResponse(PLACEHOLDER, "image/jpeg"));
            return new ImageResponse(PLACEHOLDER, "image/jpeg");
        }
    }

    private synchronized CachedImage getCached(String url) {
        CachedImage cachedImage = cache.get(url);
        if (cachedImage == null) return null;
        if (cachedImage.expiresAt().isBefore(clock.instant())) {
            cache.remove(url);
            return null;
        }
        return cachedImage;
    }

    private synchronized void putCached(String url, ImageResponse imageResponse) {
        cache.put(url, new CachedImage(imageResponse.bytes(), imageResponse.contentType(), clock.instant().plus(CACHE_TTL)));
        while (cache.size() > MAX_CACHE_ENTRIES) {
            String eldestKey = cache.keySet().iterator().next();
            cache.remove(eldestKey);
        }
    }

    public record ImageResponse(byte[] bytes, String contentType) {
    }

    private record CachedImage(byte[] bytes, String contentType, Instant expiresAt) {
    }
}
