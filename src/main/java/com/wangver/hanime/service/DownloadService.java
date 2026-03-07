package com.wangver.hanime.service;

import com.wangver.hanime.model.DownloadBatchRequest;
import com.wangver.hanime.model.DownloadProgress;
import com.wangver.hanime.model.DownloadRequestItem;
import com.wangver.hanime.model.DownloadSnapshot;
import com.wangver.hanime.model.DownloadStatus;
import com.wangver.hanime.model.DownloadTaskView;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Service
public class DownloadService {

    private static final int DEFAULT_PARALLELISM = 8;
    private static final int MAX_HISTORY_SIZE = 50;

    private final SettingsManager settingsManager;
    private final DownloadHistoryStore historyStore;
    private final DownloadResolver downloadResolver;
    private final DownloadExecutor downloadExecutor;
    private final BlockingQueue<DownloadTaskState> queue = new LinkedBlockingQueue<>();
    private final Map<String, DownloadTaskState> tasks = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final List<DownloadTaskView> history = new CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicLong sequence = new AtomicLong();

    private volatile boolean running = true;
    private volatile String activeTaskId;

    @Autowired
    public DownloadService(
            SettingsManager settingsManager,
            DownloadHistoryStore historyStore,
            HanimeParserService parserService
    ) {
        this(
                settingsManager,
                historyStore,
                request -> resolveWithParser(request, parserService),
                createDefaultExecutor()
        );
    }

    DownloadService(
            SettingsManager settingsManager,
            DownloadHistoryStore historyStore,
            DownloadResolver downloadResolver,
            DownloadExecutor downloadExecutor
    ) {
        this.settingsManager = settingsManager;
        this.historyStore = historyStore;
        this.downloadResolver = downloadResolver;
        this.downloadExecutor = downloadExecutor;
        this.history.addAll(historyStore.load());
        this.worker.submit(this::runLoop);
    }

    public DownloadSnapshot enqueue(DownloadBatchRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("至少需要一个下载任务");
        }

        for (DownloadRequestItem item : request.getItems()) {
            DownloadTaskState state = new DownloadTaskState(item, sequence.incrementAndGet());
            tasks.put(state.id, state);
            queue.offer(state);
        }

        broadcastSnapshot();
        return getSnapshot();
    }

    public DownloadSnapshot getSnapshot() {
        List<DownloadTaskView> activeTasks = tasks.values().stream()
                .filter(task -> task.id.equals(activeTaskId))
                .map(DownloadTaskState::toView)
                .toList();

        List<DownloadTaskView> queuedTasks = tasks.values().stream()
                .filter(task -> !task.id.equals(activeTaskId))
                .sorted(Comparator.comparingLong(task -> task.sequence))
                .map(DownloadTaskState::toView)
                .toList();

        return new DownloadSnapshot(activeTasks, queuedTasks, List.copyOf(history));
    }

    public DownloadSnapshot pauseTask(String taskId) {
        DownloadTaskState state = requireTask(taskId);
        state.control.pause();
        if (Objects.equals(activeTaskId, taskId)) {
            state.status = DownloadStatus.PAUSED;
            broadcastSnapshot();
        }
        return getSnapshot();
    }

    public DownloadSnapshot resumeTask(String taskId) {
        DownloadTaskState state = requireTask(taskId);
        state.control.resume();
        if (Objects.equals(activeTaskId, taskId)) {
            state.status = DownloadStatus.DOWNLOADING;
            broadcastSnapshot();
        }
        return getSnapshot();
    }

    public DownloadSnapshot cancelTask(String taskId) {
        DownloadTaskState state = requireTask(taskId);
        if (!Objects.equals(activeTaskId, taskId)) {
            queue.remove(state);
            tasks.remove(taskId);
            state.status = DownloadStatus.CANCELLED;
            state.finishedAt = now();
            addHistory(state.toView());
            historyStore.save(history);
            broadcastSnapshot();
            return getSnapshot();
        }

        state.control.cancel();
        return getSnapshot();
    }

    public DownloadSnapshot retryTask(String taskId) {
        DownloadTaskView task = history.stream()
                .filter(item -> item.getId().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到可重试的历史任务"));

        if (task.getStatus() != DownloadStatus.FAILED && task.getStatus() != DownloadStatus.CANCELLED) {
            throw new IllegalStateException("只有失败或取消的任务才能重试");
        }

        DownloadRequestItem requestItem = new DownloadRequestItem();
        requestItem.setTitle(task.getTitle());
        requestItem.setPageUrl(task.getPageUrl());
        requestItem.setDownloadUrl(task.getDownloadUrl());
        requestItem.setThumbnail(task.getThumbnail());

        DownloadBatchRequest request = new DownloadBatchRequest();
        request.setItems(List.of(requestItem));
        return enqueue(request);
    }

    public DownloadSnapshot clearHistory() {
        history.clear();
        historyStore.save(history);
        broadcastSnapshot();
        return getSnapshot();
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        sendSnapshot(emitter, getSnapshot());
        return emitter;
    }

    public void shutdown() {
        running = false;
        worker.shutdownNow();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
        emitters.forEach(SseEmitter::complete);
        emitters.clear();
    }

    @PreDestroy
    public void close() {
        shutdown();
    }

    private void runLoop() {
        while (running) {
            try {
                DownloadTaskState state = queue.take();
                process(state);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void process(DownloadTaskState state) {
        activeTaskId = state.id;
        state.status = DownloadStatus.PREPARING;
        state.startedAt = now();
        broadcastSnapshot();

        try {
            state.control.throwIfCancelled();
            ResolvedDownload resolvedDownload = resolve(state.request);
            state.control.throwIfCancelled();
            state.title = resolvedDownload.title();
            state.pageUrl = resolvedDownload.pageUrl();
            state.downloadUrl = resolvedDownload.downloadUrl();
            state.thumbnail = resolvedDownload.thumbnail();
            state.fileName = resolvedDownload.fileName();

            Path targetFile = Path.of(settingsManager.getSettings().getDownloadDirectory()).resolve(resolvedDownload.fileName());
            Path parent = targetFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            state.filePath = targetFile.toString();
            state.status = DownloadStatus.DOWNLOADING;
            broadcastSnapshot();

            downloadExecutor.download(resolvedDownload, targetFile, progress -> updateProgress(state, progress), state.control);

            state.status = DownloadStatus.COMPLETED;
            state.progressPercent = 100.0;
            state.finishedAt = now();
        } catch (DownloadCancelledException exception) {
            state.status = DownloadStatus.CANCELLED;
            state.errorMessage = "任务已取消";
            state.finishedAt = now();
        } catch (Exception exception) {
            state.status = DownloadStatus.FAILED;
            state.errorMessage = exception.getMessage();
            state.finishedAt = now();
        } finally {
            tasks.remove(state.id);
            activeTaskId = null;
            addHistory(state.toView());
            historyStore.save(history);
            broadcastSnapshot();
        }
    }

    private void updateProgress(DownloadTaskState state, DownloadProgress progress) {
        if (state.control.isCancelled()) {
            throw new DownloadCancelledException();
        }
        if (state.control.isPaused()) {
            state.status = DownloadStatus.PAUSED;
        } else {
            state.status = DownloadStatus.DOWNLOADING;
        }
        state.completedAmount = progress.completedAmount();
        state.totalAmount = progress.totalAmount();
        state.progressPercent = progress.percent();
        broadcastSnapshot();
    }

    private DownloadTaskState requireTask(String taskId) {
        DownloadTaskState state = tasks.get(taskId);
        if (state == null) {
            throw new IllegalArgumentException("任务不存在或已结束");
        }
        return state;
    }

    private ResolvedDownload resolve(DownloadRequestItem request) throws Exception {
        if (hasText(request.getDownloadUrl())) {
            return new ResolvedDownload(
                    firstText(request.getTitle(), buildTitleFromUrl(request.getDownloadUrl())),
                    request.getPageUrl(),
                    request.getDownloadUrl(),
                    request.getThumbnail(),
                    buildFileName(firstText(request.getTitle(), "video"), request.getDownloadUrl())
            );
        }
        return downloadResolver.resolve(request);
    }

    private void addHistory(DownloadTaskView view) {
        history.add(0, view);
        while (history.size() > MAX_HISTORY_SIZE) {
            history.remove(history.size() - 1);
        }
    }

    private void broadcastSnapshot() {
        DownloadSnapshot snapshot = getSnapshot();
        for (SseEmitter emitter : emitters) {
            sendSnapshot(emitter, snapshot);
        }
    }

    private void sendSnapshot(SseEmitter emitter, DownloadSnapshot snapshot) {
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(snapshot));
        } catch (IOException exception) {
            emitters.remove(emitter);
            emitter.completeWithError(exception);
        }
    }

    private String now() {
        return Instant.now().toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String firstText(String preferred, String fallback) {
        return hasText(preferred) ? preferred : fallback;
    }

    private String buildTitleFromUrl(String downloadUrl) {
        try {
            String path = URI.create(downloadUrl).getPath();
            if (path == null || path.isBlank()) {
                return "video";
            }
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            int dotIndex = fileName.lastIndexOf('.');
            return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        } catch (Exception exception) {
            return "video";
        }
    }

    private static ResolvedDownload resolveWithParser(DownloadRequestItem request, HanimeParserService parserService) throws Exception {
        if (request.getPageUrl() == null || request.getPageUrl().isBlank()) {
            throw new IOException("缺少可解析的页面地址");
        }

        Map<String, Object> parsed = parserService.parse(request.getPageUrl());
        String title = parsed.getOrDefault("title", request.getTitle()) == null
                ? "video"
                : String.valueOf(parsed.getOrDefault("title", request.getTitle()));
        String downloadUrl = parsed.get("videoUrl") == null ? "" : String.valueOf(parsed.get("videoUrl"));
        if (downloadUrl.isBlank()) {
            throw new IOException("解析成功，但未找到可下载的视频源");
        }

        String thumbnail = parsed.get("thumbnail") == null ? request.getThumbnail() : String.valueOf(parsed.get("thumbnail"));
        return new ResolvedDownload(
                title,
                request.getPageUrl(),
                downloadUrl,
                thumbnail,
                buildFileName(title, downloadUrl)
        );
    }

    private static DownloadExecutor createDefaultExecutor() {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        SegmentedFileDownloader segmentedFileDownloader = new SegmentedFileDownloader(httpClient);
        HlsDownloader hlsDownloader = new HlsDownloader(httpClient);

        return (resolvedDownload, targetFile, progressConsumer, control) -> {
            URI uri = URI.create(resolvedDownload.downloadUrl());
            if (resolvedDownload.downloadUrl().toLowerCase().contains(".m3u8")) {
                hlsDownloader.download(uri, targetFile, DEFAULT_PARALLELISM, progressConsumer, control);
            } else {
                segmentedFileDownloader.download(uri, targetFile, DEFAULT_PARALLELISM, progressConsumer, control);
            }
        };
    }

    static String buildFileName(String title, String downloadUrl) {
        String safeTitle = title == null || title.isBlank() ? "video" : title;
        safeTitle = safeTitle.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safeTitle.isBlank()) {
            safeTitle = "video";
        }

        String lowerUrl = downloadUrl == null ? "" : downloadUrl.toLowerCase();
        String extension = lowerUrl.contains(".m3u8") ? ".ts" : ".mp4";
        int queryIndex = lowerUrl.indexOf('?');
        String pathPart = queryIndex >= 0 ? lowerUrl.substring(0, queryIndex) : lowerUrl;
        int dotIndex = pathPart.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex > pathPart.lastIndexOf('/')) {
            String candidate = pathPart.substring(dotIndex);
            if (candidate.length() <= 5 && !candidate.equals(".m3u8")) {
                extension = candidate;
            }
        }

        return safeTitle + extension;
    }

    @FunctionalInterface
    interface DownloadResolver {
        ResolvedDownload resolve(DownloadRequestItem request) throws Exception;
    }

    @FunctionalInterface
    interface DownloadExecutor {
        void download(
                ResolvedDownload resolvedDownload,
                Path targetFile,
                Consumer<DownloadProgress> progressConsumer,
                DownloadTaskControl control
        ) throws Exception;
    }

    interface DownloadTaskControl {
        void pause();

        void resume();

        void cancel();

        boolean isPaused();

        boolean isCancelled();

        void awaitIfPaused() throws InterruptedException;

        void throwIfCancelled();
    }

    public record ResolvedDownload(
            String title,
            String pageUrl,
            String downloadUrl,
            String thumbnail,
            String fileName
    ) {
    }

    private static class DownloadTaskState {

        private final String id = UUID.randomUUID().toString();
        private final DownloadRequestItem request;
        private final long sequence;
        private final String createdAt = Instant.now().toString();
        private final TaskControl control = new TaskControl();

        private String title;
        private String pageUrl;
        private String downloadUrl;
        private String thumbnail;
        private String fileName;
        private String filePath;
        private DownloadStatus status = DownloadStatus.QUEUED;
        private double progressPercent;
        private long completedAmount;
        private long totalAmount;
        private String errorMessage;
        private String startedAt;
        private String finishedAt;

        private DownloadTaskState(DownloadRequestItem request, long sequence) {
            this.request = request;
            this.sequence = sequence;
            this.title = request.getTitle();
            this.pageUrl = request.getPageUrl();
            this.downloadUrl = request.getDownloadUrl();
            this.thumbnail = request.getThumbnail();
        }

        private DownloadTaskView toView() {
            return new DownloadTaskView(
                    id,
                    title,
                    pageUrl,
                    downloadUrl,
                    thumbnail,
                    fileName,
                    filePath,
                    status,
                    progressPercent,
                    completedAmount,
                    totalAmount,
                    errorMessage,
                    createdAt,
                    startedAt,
                    finishedAt
            );
        }
    }

    private static class TaskControl implements DownloadTaskControl {

        private final Object monitor = new Object();
        private volatile boolean paused;
        private volatile boolean cancelled;

        @Override
        public void pause() {
            paused = true;
        }

        @Override
        public void resume() {
            synchronized (monitor) {
                paused = false;
                monitor.notifyAll();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            synchronized (monitor) {
                paused = false;
                monitor.notifyAll();
            }
        }

        @Override
        public boolean isPaused() {
            return paused;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void awaitIfPaused() throws InterruptedException {
            synchronized (monitor) {
                while (paused && !cancelled) {
                    monitor.wait(100);
                }
            }
            throwIfCancelled();
        }

        @Override
        public void throwIfCancelled() {
            if (cancelled) {
                throw new DownloadCancelledException();
            }
        }
    }

    private static class DownloadCancelledException extends RuntimeException {
    }
}
