package com.wangver.hanime.controller;

import com.wangver.hanime.model.AppSettings;
import com.wangver.hanime.model.DownloadSnapshot;
import com.wangver.hanime.model.DownloadStatus;
import com.wangver.hanime.model.DownloadTaskView;
import com.wangver.hanime.service.DownloadService;
import com.wangver.hanime.service.HanimeBrowseService;
import com.wangver.hanime.service.HanimeParserService;
import com.wangver.hanime.service.PlaywrightBrowserService;
import com.wangver.hanime.service.SettingsManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiController.class)
class ApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HanimeParserService parserService;

    @MockBean
    private SettingsManager settingsManager;

    @MockBean
    private HanimeBrowseService browseService;

    @MockBean
    private PlaywrightBrowserService playwrightService;

    @MockBean
    private DownloadService downloadService;

    @Test
    void clearsCacheAlsoClearsDownloadHistory() throws Exception {
        when(downloadService.clearHistory()).thenReturn(snapshot());

        mockMvc.perform(post("/api/settings/clear-cache"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historyTasks[0].title").value("完成任务"));

        verify(playwrightService).forceCloseAndClearCache();
        verify(downloadService).clearHistory();
    }

    private DownloadSnapshot snapshot() {
        DownloadTaskView historyTask = new DownloadTaskView(
                "1",
                "完成任务",
                "https://hanime1.me/watch?v=1",
                "https://media.example.com/1.mp4",
                "https://image.example.com/1.jpg",
                "完成任务.mp4",
                "D:/Downloads/完成任务.mp4",
                DownloadStatus.COMPLETED,
                100.0,
                100,
                100,
                null,
                "2026-03-07T10:00:00Z",
                "2026-03-07T10:00:01Z",
                "2026-03-07T10:01:00Z"
        );
        return new DownloadSnapshot(List.of(), List.of(), List.of(historyTask));
    }
}
