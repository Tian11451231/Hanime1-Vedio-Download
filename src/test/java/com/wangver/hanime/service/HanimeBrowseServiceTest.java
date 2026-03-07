package com.wangver.hanime.service;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.InOrder;

class HanimeBrowseServiceTest {

    private static String sampleBrowseHtml() {
        return "<html><body>"
                + "<div title='[ぬるぬるアニメ] JK200人の巨乳がぶるん揺れ！運動部のムチムチ女子と汗だく乱交中出しセックス！【133分】' class='video-item-container'>"
                + "  <div class='horizontal-card'>"
                + "    <a href='https://hanime1.me/watch?v=404781' class='video-link'>"
                + "      <div class='thumb-container'><img class='main-thumb' src='https://vdownload.hembed.com/image/thumbnail/404781l.jpg'></div>"
                + "      <div class='title'>JK200人の巨乳がぶるん揺れ！運動部のムチムチ女子と汗だく乱交中出しセックス！【133分】</div>"
                + "    </a>"
                + "  </div>"
                + "</div>"
                + "</body></html>";
    }

    private static void injectBrowserService(HanimeBrowseService service, PlaywrightBrowserService browserService) throws Exception {
        Field field = HanimeBrowseService.class.getDeclaredField("browserService");
        field.setAccessible(true);
        field.set(service, browserService);
    }

    private static void stubSerializedExecution(PlaywrightBrowserService browserService) throws Exception {
        when(browserService.runSerialized(any())).thenAnswer(invocation -> {
            PlaywrightBrowserService.CheckedSupplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        });
    }

    @SuppressWarnings("unchecked")
    @Test
    void parsesMotionAnimeGridCardsUsingCurrentSiteMarkup() throws Exception {
        HanimeBrowseService service = new HanimeBrowseService();
        String html = sampleBrowseHtml();

        Method method = HanimeBrowseService.class.getDeclaredMethod("parseVideoGrid", String.class);
        method.setAccessible(true);

        List<Map<String, String>> videos = (List<Map<String, String>>) method.invoke(service, html);

        assertFalse(videos.isEmpty());
        List<String> urls = videos.stream().map(video -> video.get("url")).collect(Collectors.toList());
        assertTrue(urls.contains("https://hanime1.me/watch?v=404781"));
        Map<String, String> firstVideo = videos.stream()
                .filter(video -> "https://hanime1.me/watch?v=404781".equals(video.get("url")))
                .findFirst()
                .orElseThrow();
        assertEquals("JK200人の巨乳がぶるん揺れ！運動部のムチムチ女子と汗だく乱交中出しセックス！【133分】", firstVideo.get("title"));
    }

    @Test
    void retriesWithFreshPageAfterHeadlessTimeout() throws Exception {
        HanimeBrowseService service = new HanimeBrowseService();
        PlaywrightBrowserService browserService = mock(PlaywrightBrowserService.class);
        Page firstPage = mock(Page.class);
        Page secondPage = mock(Page.class);
        String html = sampleBrowseHtml();

        injectBrowserService(service, browserService);
        stubSerializedExecution(browserService);

        when(browserService.createPage()).thenReturn(firstPage, secondPage);
        when(browserService.isHeadless()).thenReturn(true);
        doThrow(new RuntimeException("timeout")).when(firstPage).waitForSelector(any(String.class), any(Page.WaitForSelectorOptions.class));
        when(secondPage.content()).thenReturn(html);

        Map<String, Object> result = service.fetchCategory("Motion Anime", 1);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> videos = (List<Map<String, String>>) result.get("videos");
        assertFalse(videos.isEmpty());
        verify(browserService).forceRestartHeadful();
        verify(browserService, times(2)).createPage();
        verify(firstPage).close();
        verify(secondPage).close();
    }

    @Test
    void retriesAfterHeadlessTimeoutEvenIfRestartClosedOriginalPageConnection() throws Exception {
        HanimeBrowseService service = new HanimeBrowseService();
        PlaywrightBrowserService browserService = mock(PlaywrightBrowserService.class);
        Page firstPage = mock(Page.class);
        Page secondPage = mock(Page.class);
        String html = sampleBrowseHtml();

        injectBrowserService(service, browserService);
        stubSerializedExecution(browserService);

        when(browserService.createPage()).thenReturn(firstPage, secondPage);
        when(browserService.isHeadless()).thenReturn(true);
        doThrow(new RuntimeException("timeout")).when(firstPage).waitForSelector(any(String.class), any(Page.WaitForSelectorOptions.class));
        doThrow(new com.microsoft.playwright.PlaywrightException("Playwright connection closed")).when(firstPage).close();
        when(secondPage.content()).thenReturn(html);

        assertDoesNotThrow(() -> service.fetchCategory("MMD", 1));
        verify(browserService).forceRestartHeadful();
        verify(browserService, times(2)).createPage();
    }

    @Test
    void closesPageBeforeRestartingAfterVerification() throws Exception {
        HanimeBrowseService service = new HanimeBrowseService();
        PlaywrightBrowserService browserService = mock(PlaywrightBrowserService.class);
        Page page = mock(Page.class);
        String html = sampleBrowseHtml();

        injectBrowserService(service, browserService);
        stubSerializedExecution(browserService);

        when(browserService.createPage()).thenReturn(page);
        when(browserService.markAsVerified()).thenReturn(true);
        when(page.content()).thenReturn(html);

        service.fetchCategory("Motion Anime", 1);

        InOrder inOrder = inOrder(browserService, page);
        inOrder.verify(browserService).markAsVerified();
        inOrder.verify(page).close();
        inOrder.verify(browserService).restartIfNewlyVerified(true);
    }

    @SuppressWarnings("unchecked")
    @Test
    void prefersContainerTitleAttributeWhenVisibleTitleIsMojibake() throws Exception {
        HanimeBrowseService service = new HanimeBrowseService();
        String html = "<html><body>"
                + "<div title='[Shikikat] ぺどいさん ♠BBC♠' class='video-item-container'>"
                + "<a href='https://hanime1.me/watch?v=404948'>"
                + "<img src='https://cdn.example.com/thumb.jpg'>"
                + "<div class='title'>ãºã©ããã â BBCâ </div>"
                + "</a></div>"
                + "</body></html>";

        Method method = HanimeBrowseService.class.getDeclaredMethod("parseVideoGrid", String.class);
        method.setAccessible(true);

        List<Map<String, String>> videos = (List<Map<String, String>>) method.invoke(service, html);

        assertEquals("[Shikikat] ぺどいさん ♠BBC♠", videos.get(0).get("title"));
    }

    @Test
    void resolvesExplicitSearchUrlFor2dAnimationCategory() throws Exception {
        HanimeBrowseService service = new HanimeBrowseService();

        Method method = HanimeBrowseService.class.getDeclaredMethod("buildCategoryUrl", String.class, int.class);
        method.setAccessible(true);

        String url = (String) method.invoke(service, "2D動畫", 3);

        assertEquals("https://hanime1.me/search?genre=2D%E5%8B%95%E7%95%AB&page=3", url);
    }
}
