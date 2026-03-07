package com.wangver.hanime.service;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentedFileDownloaderTest {

    @TempDir
    Path tempDir;

    @Test
    void downloadsFileUsingParallelRanges() throws Exception {
        byte[] expected = buildBinaryContent(512 * 1024);
        AtomicInteger rangeRequests = new AtomicInteger();
        HttpServer server = createRangeServer(expected, rangeRequests);

        try {
            Path outputFile = tempDir.resolve("video.mp4");
            SegmentedFileDownloader downloader = new SegmentedFileDownloader(HttpClient.newHttpClient());

            downloader.download(
                    URI.create("http://localhost:" + server.getAddress().getPort() + "/video.mp4"),
                    outputFile,
                    4,
                    progress -> {
                    }
            );

            assertArrayEquals(expected, Files.readAllBytes(outputFile));
            assertTrue(rangeRequests.get() >= 2, "expected multiple range requests for segmented download");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer createRangeServer(byte[] content, AtomicInteger rangeRequests) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/video.mp4", new RangeAwareHandler(content, rangeRequests));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    private byte[] buildBinaryContent(int size) {
        byte[] content = new byte[size];
        for (int index = 0; index < size; index++) {
            content[index] = (byte) (index % 251);
        }
        return content;
    }

    private static class RangeAwareHandler implements HttpHandler {

        private final byte[] content;
        private final AtomicInteger rangeRequests;

        private RangeAwareHandler(byte[] content, AtomicInteger rangeRequests) {
            this.content = content;
            this.rangeRequests = rangeRequests;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers responseHeaders = exchange.getResponseHeaders();
            responseHeaders.add("Accept-Ranges", "bytes");
            responseHeaders.add("Content-Type", "video/mp4");

            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                responseHeaders.add("Content-Length", String.valueOf(content.length));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }

            String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                rangeRequests.incrementAndGet();
                String[] bounds = rangeHeader.substring("bytes=".length()).split("-");
                int start = Integer.parseInt(bounds[0]);
                int end = Integer.parseInt(bounds[1]);
                byte[] chunk = Arrays.copyOfRange(content, start, end + 1);
                responseHeaders.add("Content-Range", "bytes " + start + "-" + end + "/" + content.length);
                exchange.sendResponseHeaders(206, chunk.length);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(chunk);
                }
                return;
            }

            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(content);
            }
        }
    }
}
