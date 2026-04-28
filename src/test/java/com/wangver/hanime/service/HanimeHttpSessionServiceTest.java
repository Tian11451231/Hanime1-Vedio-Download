package com.wangver.hanime.service;

import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HanimeHttpSessionServiceTest {

    @Test
    void buildsCookieHeaderFromSupportedSessionDomains() {
        PlaywrightBrowserService browserService = mock(PlaywrightBrowserService.class);
        HanimeHttpSessionService service = new HanimeHttpSessionService(browserService, (request, handler) -> new TestHttpResponse<>(200, ""));

        String header = service.buildCookieHeader(List.of(
                new Cookie("cf_clearance", "token").setDomain(".hanime1.me"),
                new Cookie("session", "abc").setDomain("javchu.com"),
                new Cookie("ignored", "nope").setDomain("example.com")
        ));

        assertEquals("cf_clearance=token; session=abc", header);
    }

    @Test
    void defaultRequestIncludesBrowserLikeHeadersAndReferer() {
        PlaywrightBrowserService browserService = mock(PlaywrightBrowserService.class);
        when(browserService.exportSessionCookies()).thenReturn(List.of(new Cookie("cf_clearance", "token").setDomain(".hanime1.me")));
        HanimeHttpSessionService service = new HanimeHttpSessionService(browserService, (request, handler) -> new TestHttpResponse<>(200, ""));

        HttpRequest request = service.buildRequest("https://hanime1.me/search?page=1", "https://hanime1.me/").GET().build();

        assertTrue(request.headers().firstValue("User-Agent").orElse("").contains("Mozilla"));
        assertTrue(request.headers().firstValue("Accept").orElse("").contains("text/html"));
        assertTrue(request.headers().firstValue("Accept-Language").orElse("").contains("zh-CN"));
        assertEquals("https://hanime1.me/", request.headers().firstValue("Referer").orElse(""));
        assertEquals("cf_clearance=token", request.headers().firstValue("Cookie").orElse(""));
    }

    @Test
    void returnsHtmlForUsableResponse() throws Exception {
        PlaywrightBrowserService browserService = mock(PlaywrightBrowserService.class);
        HanimeHttpSessionService service = new HanimeHttpSessionService(
                browserService,
                (request, handler) -> new TestHttpResponse<>(200, "<html><body>ok</body></html>")
        );

        assertEquals("<html><body>ok</body></html>", service.fetchHtml("https://hanime1.me/", "https://hanime1.me/"));
    }

    @Test
    void treatsForbiddenAndCloudflarePagesAsExpiredSession() {
        PlaywrightBrowserService browserService = mock(PlaywrightBrowserService.class);
        HanimeHttpSessionService forbiddenService = new HanimeHttpSessionService(
                browserService,
                (request, handler) -> new TestHttpResponse<>(403, "forbidden")
        );
        HanimeHttpSessionService challengeService = new HanimeHttpSessionService(
                browserService,
                (request, handler) -> new TestHttpResponse<>(200, "<html>Just a moment Cloudflare</html>")
        );

        assertThrows(HttpSessionExpiredException.class, () -> forbiddenService.fetchHtml("https://hanime1.me/", null));
        assertThrows(HttpSessionExpiredException.class, () -> challengeService.fetchHtml("https://hanime1.me/", null));
    }

    @Test
    void fetchBytesUsesBuiltRequest() throws Exception {
        PlaywrightBrowserService browserService = mock(PlaywrightBrowserService.class);
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        HanimeHttpSessionService service = new HanimeHttpSessionService(
                browserService,
                (request, handler) -> {
                    captured.set(request);
                    return new TestHttpResponse<>(200, new byte[]{1, 2, 3});
                }
        );

        assertEquals(3, service.fetchBytes("https://hanime1.me/image.jpg", "https://hanime1.me/").body().length);
        assertEquals("https://hanime1.me/", captured.get().headers().firstValue("Referer").orElse(""));
    }
}
