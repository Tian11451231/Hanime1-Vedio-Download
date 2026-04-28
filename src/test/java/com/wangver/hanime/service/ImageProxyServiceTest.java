package com.wangver.hanime.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ImageProxyServiceTest {

    @Test
    void cachesImageBytesAndContentTypeByUrl() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PlaywrightBrowserService browserService = mock(PlaywrightBrowserService.class);
        HanimeHttpSessionService httpSessionService = new HanimeHttpSessionService(
                browserService,
                (request, handler) -> {
                    calls.incrementAndGet();
                    return new TestHttpResponse<>(200, new byte[]{4, 5, 6}, Map.of("Content-Type", List.of("image/webp")));
                }
        );
        ImageProxyService service = new ImageProxyService(
                httpSessionService,
                Clock.fixed(Instant.parse("2026-04-28T00:00:00Z"), ZoneOffset.UTC)
        );

        ImageProxyService.ImageResponse first = service.fetchImage("https://cdn.example.com/cover.webp");
        ImageProxyService.ImageResponse second = service.fetchImage("https://cdn.example.com/cover.webp");

        assertArrayEquals(new byte[]{4, 5, 6}, first.bytes());
        assertEquals("image/webp", second.contentType());
        assertEquals(1, calls.get());
    }
}
