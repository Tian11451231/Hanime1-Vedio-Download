package com.wangver.hanime.model;

import java.util.List;

public record DownloadSnapshot(
        List<DownloadTaskView> activeTasks,
        List<DownloadTaskView> queuedTasks,
        List<DownloadTaskView> historyTasks
) {
}
