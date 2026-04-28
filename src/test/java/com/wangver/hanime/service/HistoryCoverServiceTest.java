package com.wangver.hanime.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryCoverServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void cachesResolvedHistoryCoverAndReusesLocalFile() throws Exception {
        AtomicInteger resolverCalls = new AtomicInteger();
        AtomicInteger imageCalls = new AtomicInteger();
        HistoryCoverService service = new HistoryCoverService(
                tempDir.resolve("history-cover-cache"),
                pageUrl -> {
                    resolverCalls.incrementAndGet();
                    return "https://cdn.example.com/cover.jpg";
                },
                imageUrl -> {
                    imageCalls.incrementAndGet();
                    return new ImageProxyService.ImageResponse(new byte[]{7, 8, 9}, "image/jpeg");
                }
        );

        ImageProxyService.ImageResponse first = service.fetchCover("https://hanime1.me/watch?v=102579");
        ImageProxyService.ImageResponse second = service.fetchCover("https://hanime1.me/watch?v=102579");

        assertArrayEquals(new byte[]{7, 8, 9}, first.bytes());
        assertArrayEquals(new byte[]{7, 8, 9}, second.bytes());
        assertEquals("image/jpeg", second.contentType());
        assertEquals(1, resolverCalls.get());
        assertEquals(1, imageCalls.get());
        try (Stream<Path> cacheEntries = Files.list(tempDir.resolve("history-cover-cache"))) {
            assertTrue(cacheEntries.findAny().isPresent());
        }
    }

    @Test
    void resolvesRelativeThumbnailUrlAgainstVideoPageBeforeFetchingImage() throws Exception {
        AtomicReference<String> fetchedUrl = new AtomicReference<>();
        HistoryCoverService service = new HistoryCoverService(
                tempDir.resolve("history-cover-cache"),
                pageUrl -> "/image/thumbnail/cover.jpg",
                imageUrl -> {
                    fetchedUrl.set(imageUrl);
                    return new ImageProxyService.ImageResponse(new byte[]{1}, "image/jpeg");
                }
        );

        service.fetchCover("https://hanime1.me/watch?v=102579");

        assertEquals("https://hanime1.me/image/thumbnail/cover.jpg", fetchedUrl.get());
    }

    @Test
    void cachesByVideoIdSoEquivalentWatchUrlsDoNotRefetchPage() throws Exception {
        AtomicInteger resolverCalls = new AtomicInteger();
        HistoryCoverService service = new HistoryCoverService(
                tempDir.resolve("history-cover-cache"),
                pageUrl -> {
                    resolverCalls.incrementAndGet();
                    return "https://cdn.example.com/same-cover.jpg";
                },
                imageUrl -> new ImageProxyService.ImageResponse(new byte[]{3}, "image/jpeg")
        );

        service.fetchCover("https://hanime1.me/watch?v=102579");
        service.fetchCover("https://hanime1.me/watch?v=102579&from=history");

        assertEquals(1, resolverCalls.get());
    }
}
