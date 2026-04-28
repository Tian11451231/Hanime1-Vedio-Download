package com.wangver.hanime.service;

import com.wangver.hanime.model.DownloadProgress;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class HlsDownloader {

    private final HttpClient httpClient;

    public HlsDownloader(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void download(URI playlistUri, Path targetFile, int parallelism, Consumer<DownloadProgress> progressConsumer)
            throws IOException, InterruptedException {
        download(playlistUri, targetFile, parallelism, progressConsumer, new NoOpTaskControl());
    }

    public void download(
            URI playlistUri,
            Path targetFile,
            int parallelism,
            Consumer<DownloadProgress> progressConsumer,
            DownloadService.DownloadTaskControl control
    )
            throws IOException, InterruptedException {
        control.awaitIfPaused();
        control.throwIfCancelled();
        URI mediaPlaylistUri = resolveMediaPlaylistUri(playlistUri);
        List<URI> segments = readSegmentUris(mediaPlaylistUri);
        if (segments.isEmpty()) {
            throw new IOException("未在 m3u8 中找到可下载分片");
        }

        Path parent = targetFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path stagingDir = Files.createTempDirectory(parent != null ? parent : Path.of("."), "hls-");
        ExecutorService executorService = Executors.newFixedThreadPool(Math.max(1, parallelism));
        List<Future<Path>> futures = new ArrayList<>();
        AtomicLong completedSegments = new AtomicLong();

        try {
            for (int index = 0; index < segments.size(); index++) {
                int currentIndex = index;
                URI segmentUri = segments.get(index);
                futures.add(executorService.submit(() -> downloadSegment(
                        segmentUri,
                        stagingDir.resolve(currentIndex + ".ts"),
                        completedSegments,
                        segments.size(),
                        progressConsumer,
                        control
                )));
            }

            List<Path> orderedSegments = new ArrayList<>();
            for (Future<Path> future : futures) {
                orderedSegments.add(future.get());
            }

            mergeSegments(orderedSegments, targetFile);
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw interruptedException;
            }
            throw new IOException("HLS 下载失败", cause);
        } finally {
            executorService.shutdownNow();
            deleteDirectory(stagingDir);
        }
    }

    private URI resolveMediaPlaylistUri(URI playlistUri) throws IOException, InterruptedException {
        String playlist = fetchText(playlistUri);
        if (!playlist.contains("#EXT-X-STREAM-INF")) {
            return playlistUri;
        }

        List<VariantPlaylist> variants = new ArrayList<>();
        String[] lines = playlist.split("\\R");
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF")) {
                continue;
            }

            long bandwidth = parseBandwidth(line);
            String uriLine = nextUriLine(lines, index + 1);
            if (uriLine != null) {
                variants.add(new VariantPlaylist(bandwidth, playlistUri.resolve(uriLine)));
            }
        }

        return variants.stream()
                .max(Comparator.comparingLong(VariantPlaylist::bandwidth))
                .map(VariantPlaylist::uri)
                .orElseThrow(() -> new IOException("未找到可用的 HLS 清单"));
    }

    private List<URI> readSegmentUris(URI mediaPlaylistUri) throws IOException, InterruptedException {
        String playlist = fetchText(mediaPlaylistUri);
        List<URI> segments = new ArrayList<>();
        for (String rawLine : playlist.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            segments.add(mediaPlaylistUri.resolve(line));
        }
        return segments;
    }

    private Path downloadSegment(
            URI segmentUri,
            Path outputFile,
            AtomicLong completedSegments,
            int totalSegments,
            Consumer<DownloadProgress> progressConsumer,
            DownloadService.DownloadTaskControl control
    ) throws IOException, InterruptedException {
        control.awaitIfPaused();
        control.throwIfCancelled();
        HttpResponse<InputStream> response = httpClient.send(
                requestBuilder(segmentUri).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        if (response.statusCode() >= 400) {
            throw new IOException("分片下载失败，状态码: " + response.statusCode());
        }

        try (InputStream inputStream = response.body();
             OutputStream outputStream = Files.newOutputStream(
                     outputFile,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE
             )) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                control.awaitIfPaused();
                control.throwIfCancelled();
                outputStream.write(buffer, 0, bytesRead);
            }
        }

        long finished = completedSegments.incrementAndGet();
        progressConsumer.accept(new DownloadProgress(finished, totalSegments));
        return outputFile;
    }

    private void mergeSegments(List<Path> segments, Path targetFile) throws IOException {
        try (BufferedOutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(
                targetFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ))) {
            for (Path segment : segments) {
                Files.copy(segment, outputStream);
            }
        }
    }

    private String fetchText(URI uri) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(
                requestBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() >= 400) {
            throw new IOException("获取播放列表失败，状态码: " + response.statusCode());
        }
        return response.body();
    }

    private long parseBandwidth(String line) {
        String[] pieces = line.split(",");
        for (String piece : pieces) {
            String trimmed = piece.trim();
            if (trimmed.startsWith("BANDWIDTH=")) {
                return Long.parseLong(trimmed.substring("BANDWIDTH=".length()));
            }
        }
        return 0L;
    }

    private String nextUriLine(String[] lines, int startIndex) {
        for (int index = startIndex; index < lines.length; index++) {
            String candidate = lines[index].trim();
            if (!candidate.isEmpty() && !candidate.startsWith("#")) {
                return candidate;
            }
        }
        return null;
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private HttpRequest.Builder requestBuilder(URI uri) {
        return HttpRequest.newBuilder(uri).header("User-Agent", "Mozilla/5.0");
    }

    private static class NoOpTaskControl implements DownloadService.DownloadTaskControl {

        @Override
        public void pause() {
        }

        @Override
        public void resume() {
        }

        @Override
        public void cancel() {
        }

        @Override
        public boolean isPaused() {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void awaitIfPaused() {
        }

        @Override
        public void throwIfCancelled() {
        }
    }

    private record VariantPlaylist(long bandwidth, URI uri) {
    }
}
