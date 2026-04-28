package com.wangver.hanime.service;

import com.microsoft.playwright.options.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class HanimeHttpSessionService {

    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final Set<String> SESSION_DOMAINS = Set.of("hanime1.me", "javchu.com");

    private final PlaywrightBrowserService browserService;
    private final HttpTransport transport;

    @Autowired
    public HanimeHttpSessionService(PlaywrightBrowserService browserService) {
        this(browserService, createDefaultTransport());
    }

    HanimeHttpSessionService(PlaywrightBrowserService browserService, HttpTransport transport) {
        this.browserService = browserService;
        this.transport = transport;
    }

    public String fetchHtml(String url, String referer) throws IOException, InterruptedException {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = (HttpResponse<String>) transport.send(buildRequest(url, referer).GET().build(), HttpResponse.BodyHandlers.ofString());
        ensureUsableResponse(response.statusCode(), response.body());
        return response.body();
    }

    public HttpResponse<byte[]> fetchBytes(String url, String referer) throws IOException, InterruptedException {
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> response = (HttpResponse<byte[]>) transport.send(buildRequest(url, referer).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new IOException("资源请求失败，状态码: " + response.statusCode());
        }
        return response;
    }

    HttpRequest.Builder buildRequest(String url, String referer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Cache-Control", "no-cache");

        String cookieHeader = buildCookieHeader(browserService.exportSessionCookies());
        if (!cookieHeader.isBlank()) {
            builder.header("Cookie", cookieHeader);
        }
        if (referer != null && !referer.isBlank()) {
            builder.header("Referer", referer);
        }
        return builder;
    }

    String buildCookieHeader(List<Cookie> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return "";
        }
        return cookies.stream()
                .filter(cookie -> cookie != null && cookie.name != null && cookie.value != null)
                .filter(cookie -> isSessionDomain(cookie.domain))
                .map(cookie -> cookie.name + "=" + cookie.value)
                .collect(Collectors.joining("; "));
    }

    private boolean isSessionDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return false;
        }
        String normalized = domain.toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        String finalNormalized = normalized;
        return SESSION_DOMAINS.stream().anyMatch(domainName -> finalNormalized.equals(domainName) || finalNormalized.endsWith("." + domainName));
    }

    private void ensureUsableResponse(int statusCode, String body) {
        if (statusCode == 401 || statusCode == 403 || statusCode == 429 || statusCode >= 500) {
            throw expired("HTTP 会话已失效，请清理浏览器缓存或重新打开浏览器完成验证");
        }
        if (body == null || body.isBlank()) {
            throw expired("HTTP 会话返回空页面，请重新完成浏览器验证");
        }
        String lowerBody = body.toLowerCase(Locale.ROOT);
        if (lowerBody.contains("cf-challenge")
                || lowerBody.contains("cloudflare")
                || lowerBody.contains("just a moment")
                || lowerBody.contains("checking your browser")) {
            throw expired("HTTP 会话被 Cloudflare 拦截，请重新完成浏览器验证");
        }
    }

    private HttpSessionExpiredException expired(String message) {
        return new HttpSessionExpiredException(message);
    }

    private static HttpTransport createDefaultTransport() {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        return httpClient::send;
    }

    @FunctionalInterface
    interface HttpTransport {
        HttpResponse<?> send(HttpRequest request, HttpResponse.BodyHandler<?> handler) throws IOException, InterruptedException;
    }
}
