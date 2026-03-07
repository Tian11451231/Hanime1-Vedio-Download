package com.wangver.hanime.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class HlsDownloaderTest {

    @TempDir
    Path tempDir;

    @Test
    void downloadsHighestBandwidthVariantAndMergesSegments() throws Exception {
        Map<String, byte[]> payloads = new HashMap<>();
        payloads.put("/master.m3u8", ("#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=3000\n"
                + "high/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1200\n"
                + "low/index.m3u8\n").getBytes(StandardCharsets.UTF_8));
        payloads.put("/high/index.m3u8", ("#EXTM3U\n"
                + "#EXTINF:5,\n"
                + "seg-1.ts\n"
                + "#EXTINF:5,\n"
                + "seg-2.ts\n").getBytes(StandardCharsets.UTF_8));
        payloads.put("/low/index.m3u8", ("#EXTM3U\n"
                + "#EXTINF:5,\n"
                + "seg-1.ts\n").getBytes(StandardCharsets.UTF_8));
        payloads.put("/high/seg-1.ts", "HIGH-ONE".getBytes(StandardCharsets.UTF_8));
        payloads.put("/high/seg-2.ts", "HIGH-TWO".getBytes(StandardCharsets.UTF_8));
        payloads.put("/low/seg-1.ts", "LOW".getBytes(StandardCharsets.UTF_8));

        HttpServer server = createServer(payloads);

        try {
            Path outputFile = tempDir.resolve("video.ts");
            HlsDownloader downloader = new HlsDownloader(HttpClient.newHttpClient());

            downloader.download(
                    URI.create("http://localhost:" + server.getAddress().getPort() + "/master.m3u8"),
                    outputFile,
                    3,
                    progress -> {
                    }
            );

            assertArrayEquals("HIGH-ONEHIGH-TWO".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(outputFile));
        } finally {
            server.stop(0);
        }
    }

    private HttpServer createServer(Map<String, byte[]> payloads) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> writePayload(exchange, payloads));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    private void writePayload(HttpExchange exchange, Map<String, byte[]> payloads) throws IOException {
        byte[] payload = payloads.get(exchange.getRequestURI().getPath());
        if (payload == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(payload);
        }
    }
}
