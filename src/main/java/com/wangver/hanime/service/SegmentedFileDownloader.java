package com.wangver.hanime.service;

import com.wangver.hanime.model.DownloadProgress;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class SegmentedFileDownloader {

    private final HttpClient httpClient;

    public SegmentedFileDownloader(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void download(URI sourceUri, Path targetFile, int parallelism, Consumer<DownloadProgress> progressConsumer)
            throws IOException, InterruptedException {
        download(sourceUri, targetFile, parallelism, progressConsumer, new NoOpTaskControl());
    }

    public void download(
            URI sourceUri,
            Path targetFile,
            int parallelism,
            Consumer<DownloadProgress> progressConsumer,
            DownloadService.DownloadTaskControl control
    )
            throws IOException, InterruptedException {
        HttpResponse<Void> headResponse = httpClient.send(
                requestBuilder(sourceUri).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding()
        );

        long contentLength = parseContentLength(headResponse);
        boolean supportsRanges = headResponse.headers()
                .firstValue("Accept-Ranges")
                .map(value -> value.equalsIgnoreCase("bytes"))
                .orElse(false);

        Path tempFile = prepareTargetFile(targetFile);
        if (supportsRanges && contentLength > 0 && parallelism > 1) {
            downloadInSegments(sourceUri, tempFile, contentLength, parallelism, progressConsumer, control);
        } else {
            downloadSequentially(sourceUri, tempFile, contentLength, progressConsumer, control);
        }

        Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
    }

    private void downloadInSegments(
            URI sourceUri,
            Path tempFile,
            long contentLength,
            int parallelism,
            Consumer<DownloadProgress> progressConsumer,
            DownloadService.DownloadTaskControl control
    ) throws IOException, InterruptedException {
        Files.deleteIfExists(tempFile);
        try (FileChannel fileChannel = FileChannel.open(
                tempFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            fileChannel.truncate(contentLength);
        }

        long chunkSize = Math.max(1, (contentLength + parallelism - 1) / parallelism);
        AtomicLong completed = new AtomicLong();
        ExecutorService executorService = Executors.newFixedThreadPool(parallelism);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (long start = 0; start < contentLength; start += chunkSize) {
                long chunkStart = start;
                long chunkEnd = Math.min(contentLength - 1, chunkStart + chunkSize - 1);
                futures.add(executorService.submit(() -> {
                    downloadChunk(sourceUri, tempFile, chunkStart, chunkEnd, contentLength, completed, progressConsumer, control);
                    return null;
                }));
            }

            for (Future<?> future : futures) {
                future.get();
            }
        } catch (ExecutionException executionException) {
            Files.deleteIfExists(tempFile);
            Throwable cause = executionException.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw interruptedException;
            }
            throw new IOException("分片下载失败", cause);
        } finally {
            executorService.shutdownNow();
        }
    }

    private void downloadChunk(
            URI sourceUri,
            Path tempFile,
            long start,
            long end,
            long contentLength,
            AtomicLong completed,
            Consumer<DownloadProgress> progressConsumer,
            DownloadService.DownloadTaskControl control
    ) throws IOException, InterruptedException {
        control.awaitIfPaused();
        control.throwIfCancelled();
        HttpRequest request = requestBuilder(sourceUri)
                .header("Range", "bytes=" + start + "-" + end)
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 206 && response.statusCode() != 200) {
            throw new IOException("分片请求失败，状态码: " + response.statusCode());
        }

        try (InputStream inputStream = response.body();
             FileChannel fileChannel = FileChannel.open(tempFile, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            long position = start;
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                control.awaitIfPaused();
                control.throwIfCancelled();
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, read);
                while (byteBuffer.hasRemaining()) {
                    position += fileChannel.write(byteBuffer, position);
                }
                long finished = completed.addAndGet(read);
                progressConsumer.accept(new DownloadProgress(finished, contentLength));
            }
        }
    }

    private void downloadSequentially(
            URI sourceUri,
            Path tempFile,
            long contentLength,
            Consumer<DownloadProgress> progressConsumer,
            DownloadService.DownloadTaskControl control
    ) throws IOException, InterruptedException {
        Files.deleteIfExists(tempFile);
        control.awaitIfPaused();
        control.throwIfCancelled();
        HttpResponse<InputStream> response = httpClient.send(
                requestBuilder(sourceUri).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );

        if (response.statusCode() >= 400) {
            throw new IOException("下载请求失败，状态码: " + response.statusCode());
        }

        long totalAmount = contentLength > 0 ? contentLength : parseContentLength(response);
        long completed = 0;
        try (InputStream inputStream = response.body();
             FileChannel fileChannel = FileChannel.open(
                     tempFile,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING
             )) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                control.awaitIfPaused();
                control.throwIfCancelled();
                ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, read);
                while (byteBuffer.hasRemaining()) {
                    fileChannel.write(byteBuffer);
                }
                completed += read;
                progressConsumer.accept(new DownloadProgress(completed, totalAmount));
            }
        }
    }

    private Path prepareTargetFile(Path targetFile) throws IOException {
        Path parent = targetFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return targetFile.resolveSibling(targetFile.getFileName() + ".part");
    }

    private long parseContentLength(HttpResponse<?> response) {
        return response.headers()
                .firstValue("Content-Length")
                .map(Long::parseLong)
                .orElse(-1L);
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
}
