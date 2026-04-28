package com.wangver.hanime.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class PlaywrightBrowserService {

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private Playwright playwright;
    private BrowserContext context;
    private Page sessionPage;
    private ScheduledExecutorService sessionRefresher;
    private final ReentrantLock executionLock = new ReentrantLock(true);

    @PostConstruct
    public void start() {
        System.out.println("Starting Playwright background engine...");
        init();
        startSessionKeepAlive();
    }

    private synchronized void init() {
        if (playwright != null) return;

        System.out.println("Initializing Playwright Persistent Browser Service...");

        Playwright.CreateOptions createOptions = new Playwright.CreateOptions();
        createOptions.setEnv(Map.of(
            "PLAYWRIGHT_BROWSERS_PATH", "0",
            "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"
        ));

        playwright = Playwright.create(createOptions);

        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
            .setHeadless(false)
            .setChannel("msedge");

        options.setArgs(buildLaunchArgs());

        try {
            Path dataPath = AppPaths.playwrightDataDir();
            context = playwright.chromium().launchPersistentContext(dataPath, options);
        } catch (Exception e) {
            System.out.println("Failed to launch Edge, trying Chrome...");
            options.setChannel("chrome");
            Path dataPath = AppPaths.playwrightDataDir();
            context = playwright.chromium().launchPersistentContext(dataPath, options);
        }

        System.out.println("Playwright Persistent Browser Context Ready.");

        sessionPage = context.newPage();
        sessionPage.navigate("https://hanime1.me/",
            new Page.NavigateOptions().setTimeout(60000));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("JVM Shutdown Hook Triggered: Forcibly closing Playwright browser...");
            this.close();
        }));
    }

    private void startSessionKeepAlive() {
        sessionRefresher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-refresher");
            t.setDaemon(true);
            return t;
        });
        sessionRefresher.scheduleAtFixedRate(() -> {
            try {
                sessionPage.evaluate("document.title");
            } catch (Exception e) {
                try {
                    sessionPage.navigate("https://hanime1.me/",
                        new Page.NavigateOptions().setTimeout(30000));
                } catch (Exception ignored) {}
            }
        }, 10, 10, TimeUnit.MINUTES);
    }

    public synchronized Page createPage() {
        if (context == null) init();
        try {
            return context.newPage();
        } catch (PlaywrightException e) {
            System.err.println("Playwright Context error when creating page: " + e.getMessage());
            System.err.println("Attempting to restart browser...");
            close();
            init();
            return context.newPage();
        }
    }

    public synchronized List<Cookie> exportSessionCookies() {
        if (context == null) init();
        try {
            return context.cookies(List.of("https://hanime1.me", "https://javchu.com"));
        } catch (PlaywrightException exception) {
            System.err.println("Failed to export browser cookies: " + exception.getMessage());
            return List.of();
        }
    }

    public <T> T runSerialized(CheckedSupplier<T> action) throws Exception {
        executionLock.lock();
        try {
            return action.get();
        } finally {
            executionLock.unlock();
        }
    }

    public boolean hasVerifiedSession() {
        return true;
    }

    List<String> buildLaunchArgs() {
        return List.of(
            "--disable-blink-features=AutomationControlled"
        );
    }

    @PreDestroy
    public void close() {
        if (sessionRefresher != null) {
            sessionRefresher.shutdownNow();
            sessionRefresher = null;
        }
        if (sessionPage != null) {
            sessionPage.close();
            sessionPage = null;
        }
        if (context != null) {
            context.close();
            context = null;
        }
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
        System.out.println("Playwright Service Closed.");
    }

    public void forceCloseAndClearCache() {
        close();
        try {
            Path dataPath = AppPaths.playwrightDataDir();
            if (java.nio.file.Files.exists(dataPath)) {
                System.out.println("Force clearing browser data directory: " + dataPath);
                deleteDirectoryRecursively(dataPath);
            }
        } catch (Exception e) {
            System.err.println("Failed to clear data directory: " + e.getMessage());
        }
    }

    private void deleteDirectoryRecursively(Path path) throws java.io.IOException {
        if (java.nio.file.Files.isDirectory(path)) {
            try (java.util.stream.Stream<Path> entries = java.nio.file.Files.list(path)) {
                entries.forEach(entry -> {
                    try {
                        deleteDirectoryRecursively(entry);
                    } catch (java.io.IOException e) {
                        e.printStackTrace();
                    }
                });
            }
        }
        java.nio.file.Files.delete(path);
    }
}
