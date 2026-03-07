package com.wangver.hanime.service;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HanimeParserServiceTest {

    private static String sampleDownloadPageHtml() {
        return "<html><body>"
                + "<table class='download-table'>"
                + "<tr><td></td><td>720p</td><td><a data-url='https://rapidgator.net/file/lower-quality'></a></td></tr>"
                + "<tr><td></td><td>1080p</td><td><a data-url='https://rapidgator.net/file/79865d10b7482c731a481421e4d2a35d'></a></td></tr>"
                + "</table>"
                + "</body></html>";
    }

    private static String sampleRealPlaylistHtml() {
        return "<html><body>"
                + "<div id='video-playlist-wrapper'>"
                + "  <div class='related-watch-wrap multiple-link-wrapper'>"
                + "    <a class='overlay' href='https://javchu.com/watch?v=166722'></a>"
                + "    <div class='card-mobile-panel inner'>"
                + "      <img src='https://vdownload.hembed.com/image/icon/card_doujin_background.jpg?secure=bg'>"
                + "      <img src='https://vdownload.hembed.com/image/thumbnail/166722l.jpg?secure=real' alt='JUR-556 我的新媽媽加奈就在我眼前被輪姦了-水戸かな'>"
                + "      <div class='card-mobile-title'>JUR-556 我的新媽媽加奈就在我眼前被輪姦了-水戸かな</div>"
                + "    </div>"
                + "  </div>"
                + "</div>"
                + "</body></html>";
    }

    private static void injectBrowserService(HanimeParserService service, PlaywrightBrowserService browserService) throws Exception {
        Field field = HanimeParserService.class.getDeclaredField("browserService");
        field.setAccessible(true);
        field.set(service, browserService);
    }

    private static void stubSerializedExecution(PlaywrightBrowserService browserService) throws Exception {
        when(browserService.runSerialized(any())).thenAnswer(invocation -> {
            PlaywrightBrowserService.CheckedSupplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        });
    }

    @Test
    void prefersHighestQualityMp4FromDownloadTable() throws Exception {
        HanimeParserService service = new HanimeParserService();
        String html = sampleDownloadPageHtml();

        Method method = HanimeParserService.class.getDeclaredMethod("extractHighestQualityStream", String.class);
        method.setAccessible(true);

        String stream = (String) method.invoke(service, html);

        assertEquals("https://rapidgator.net/file/79865d10b7482c731a481421e4d2a35d", stream);
    }

    @Test
    void prefersDownloadPageSourceOverHomepageStreamWhenBothExist() throws Exception {
        HanimeParserService service = new HanimeParserService();
        String pageHtml = "<html><body><source src='https://cdn.example.com/video.m3u8'></body></html>";
        String downloadHtml = sampleDownloadPageHtml();

        Method method = HanimeParserService.class.getDeclaredMethod("selectBestDownloadSource", String.class, String.class);
        method.setAccessible(true);

        String stream = (String) method.invoke(service, pageHtml, downloadHtml);

        assertEquals("https://rapidgator.net/file/79865d10b7482c731a481421e4d2a35d", stream);
    }

    @Test
    void prefers1080DirectMp4FromWatchPageOverLowerQualitySourceTag() throws Exception {
        HanimeParserService service = new HanimeParserService();
        String html = "<html><body>"
                + "<video><source src='https://cdn.example.com/video-720p.mp4'></video>"
                + "<a href='https://cdn.example.com/video-1080p.mp4'>download</a>"
                + "</body></html>";

        Method method = HanimeParserService.class.getDeclaredMethod("extractHighestQualityStream", String.class);
        method.setAccessible(true);

        String stream = (String) method.invoke(service, html);

        assertEquals("https://cdn.example.com/video-1080p.mp4", stream);
    }

    @Test
    void prefers2160pDirectMp4Over1080pWhenBothExist() throws Exception {
        HanimeParserService service = new HanimeParserService();
        String html = "<html><body>"
                + "<a href='https://cdn.example.com/video-1080p.mp4'>1080p</a>"
                + "<a href='https://cdn.example.com/video-2160p.mp4'>2160p</a>"
                + "</body></html>";

        Method method = HanimeParserService.class.getDeclaredMethod("extractHighestQualityStream", String.class);
        method.setAccessible(true);

        String stream = (String) method.invoke(service, html);

        assertEquals("https://cdn.example.com/video-2160p.mp4", stream);
    }

    @Test
    void usesLegalDownloadPageSelectorWhenFetchingDirectLinks() throws Exception {
        HanimeParserService service = new HanimeParserService();
        Page page = mock(Page.class);

        when(page.content()).thenReturn("<html><body><table class='download-table'><tr><td></td><td>1080p</td><td><a data-url='https://cdn.example.com/video-1080p.mp4'></a></td></tr></table></body></html>");

        Method method = HanimeParserService.class.getDeclaredMethod("fetchDownloadPageHtml", Page.class, String.class);
        method.setAccessible(true);

        String html = (String) method.invoke(service, page, "166763");

        verify(page).waitForSelector(anyString(), any(Page.WaitForSelectorOptions.class));
        org.mockito.ArgumentCaptor<String> selectorCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(page).waitForSelector(selectorCaptor.capture(), any(Page.WaitForSelectorOptions.class));
        String selector = selectorCaptor.getValue();

        assertNotNull(html);
        assertFalse(selector.contains("text:1080p"));
        assertEquals("table.download-table a[data-url], a[data-url]", selector);
    }

    @SuppressWarnings("unchecked")
    @Test
    void extractsPlaylistItemsFromOverlayBasedSeriesMarkup() throws Exception {
        HanimeParserService service = new HanimeParserService();
        String html = "<html><body>"
                + "<div id='video-playlist-wrapper'>"
                + "  <div class='related-watch-wrap multiple-link-wrapper current'>"
                + "    <a class='overlay' href='/watch?v=2001'></a>"
                + "    <div class='card-mobile-panel inner'>"
                + "      <img src='https://cdn.example.com/current-background.jpg'>"
                + "      <img src='https://cdn.example.com/thumb-2001.jpg' alt='系列第1集'>"
                + "      <div class='card-mobile-title'>系列第1集</div>"
                + "    </div>"
                + "  </div>"
                + "</div>"
                + "<div id='video-playlist-wrapper'>"
                + "  <div class='related-watch-wrap multiple-link-wrapper'>"
                + "    <a class='overlay' href='/watch?v=2002'></a>"
                + "    <div class='card-mobile-panel inner'>"
                + "      <img src='https://cdn.example.com/thumb-2002.jpg' alt='系列第2集'>"
                + "      <div class='card-mobile-title'>系列第2集</div>"
                + "    </div>"
                + "  </div>"
                + "  <div class='related-watch-wrap multiple-link-wrapper'>"
                + "    <a class='overlay' href='https://hanime1.me/watch?v=2003'></a>"
                + "    <div class='card-mobile-panel inner'>"
                + "      <img data-src='https://cdn.example.com/thumb-2003.jpg' alt='系列第3集'>"
                + "      <div class='card-mobile-title'>系列第3集</div>"
                + "    </div>"
                + "  </div>"
                + "</div>"
                + "</body></html>";

        Method method = HanimeParserService.class.getDeclaredMethod("extractPlaylist", String.class, String.class);
        method.setAccessible(true);

        List<Map<String, String>> playlist = (List<Map<String, String>>) method.invoke(service, html, "https://hanime1.me/watch?v=2001");

        assertEquals(3, playlist.size());
        assertEquals("https://hanime1.me/watch?v=2001", playlist.get(0).get("url"));
        assertEquals("系列第1集", playlist.get(0).get("title"));
        assertEquals("https://cdn.example.com/thumb-2001.jpg", playlist.get(0).get("thumbnail"));
        assertEquals("https://hanime1.me/watch?v=2002", playlist.get(1).get("url"));
        assertEquals("系列第2集", playlist.get(1).get("title"));
        assertEquals("https://cdn.example.com/thumb-2002.jpg", playlist.get(1).get("thumbnail"));
        assertEquals("https://hanime1.me/watch?v=2003", playlist.get(2).get("url"));
        assertEquals("系列第3集", playlist.get(2).get("title"));
        assertEquals("https://cdn.example.com/thumb-2003.jpg", playlist.get(2).get("thumbnail"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void prefersRealThumbnailOverBackgroundPlaceholderInPlaylist() throws Exception {
        HanimeParserService service = new HanimeParserService();
        String html = "<html><body>"
                + "<div id='video-playlist-wrapper'>"
                + "  <div class='related-watch-wrap multiple-link-wrapper'>"
                + "    <a class='overlay' href='/watch?v=3001'></a>"
                + "    <div class='card-mobile-panel inner'>"
                + "      <img src='https://vdownload.hembed.com/image/icon/card_doujin_background.jpg?secure=abc'>"
                + "      <img src='https://vdownload.hembed.com/image/thumbnail/3001l.jpg?secure=thumb'>"
                + "      <div class='card-mobile-title'>真实封面项</div>"
                + "    </div>"
                + "  </div>"
                + "</div>"
                + "</body></html>";

        Method method = HanimeParserService.class.getDeclaredMethod("extractPlaylist", String.class, String.class);
        method.setAccessible(true);

        List<Map<String, String>> playlist = (List<Map<String, String>>) method.invoke(service, html, "https://hanime1.me/watch?v=3001");

        assertEquals(1, playlist.size());
        assertEquals("https://vdownload.hembed.com/image/thumbnail/3001l.jpg?secure=thumb", playlist.get(0).get("thumbnail"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void extractsSeriesListFromRealPlaylistHtmlSample() throws Exception {
        HanimeParserService service = new HanimeParserService();
        String html = sampleRealPlaylistHtml();

        Method method = HanimeParserService.class.getDeclaredMethod("extractPlaylist", String.class, String.class);
        method.setAccessible(true);

        List<Map<String, String>> playlist = (List<Map<String, String>>) method.invoke(service, html, "https://javchu.com/watch?v=92205");

        assertFalse(playlist.isEmpty());
        assertTrue(playlist.stream().anyMatch(item -> "https://javchu.com/watch?v=166722".equals(item.get("url"))
                && "JUR-556 我的新媽媽加奈就在我眼前被輪姦了-水戸かな".equals(item.get("title"))));
    }

    @Test
    void closesPageBeforeRestartingAfterVerification() throws Exception {
        HanimeParserService service = new HanimeParserService();
        PlaywrightBrowserService browserService = mock(PlaywrightBrowserService.class);
        Page page = mock(Page.class);

        injectBrowserService(service, browserService);
        stubSerializedExecution(browserService);

        when(browserService.createPage()).thenReturn(page);
        when(browserService.markAsVerified()).thenReturn(true);
        when(page.content()).thenReturn("<html><head><title>Example</title></head><body><h1 class='title'>Example</h1><video><source src='https://cdn.example.com/video-1080p.mp4'></video></body></html>");
        doThrow(new RuntimeException("download page unavailable")).when(page).waitForSelector(any(String.class), any(Page.WaitForSelectorOptions.class));

        service.parse("https://hanime1.me/watch?v=166763");

        InOrder inOrder = inOrder(browserService, page);
        inOrder.verify(browserService).markAsVerified();
        inOrder.verify(page).close();
        inOrder.verify(browserService).restartIfNewlyVerified(true);
    }

    @Test
    void retriesAfterHeadlessTimeoutEvenIfRestartClosedOriginalPageConnection() throws Exception {
        HanimeParserService service = new HanimeParserService();
        PlaywrightBrowserService browserService = mock(PlaywrightBrowserService.class);
        Page firstPage = mock(Page.class);
        Page secondPage = mock(Page.class);

        injectBrowserService(service, browserService);
        stubSerializedExecution(browserService);

        when(browserService.createPage()).thenReturn(firstPage, secondPage);
        when(browserService.isHeadless()).thenReturn(true, false);
        doThrow(new RuntimeException("timeout")).when(firstPage).waitForSelector(any(String.class), any(Page.WaitForSelectorOptions.class));
        doThrow(new com.microsoft.playwright.PlaywrightException("Playwright connection closed")).when(firstPage).close();
        when(secondPage.content()).thenReturn("<html><head><title>Example</title></head><body><h1 class='title'>Example</h1><video><source src='https://cdn.example.com/video-1080p.mp4'></video></body></html>");
        when(secondPage.waitForSelector(any(String.class), any(Page.WaitForSelectorOptions.class))).thenAnswer(invocation -> {
            String selector = invocation.getArgument(0);
            if (selector.contains("table.download-table") || selector.contains("a[data-url]")) {
                throw new RuntimeException("download page unavailable");
            }
            return null;
        });

        assertDoesNotThrow(() -> service.parse("https://hanime1.me/watch?v=166763"));
        verify(browserService).forceRestartHeadful();
    }
}
