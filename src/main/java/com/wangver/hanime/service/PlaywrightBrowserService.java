package com.wangver.hanime.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class PlaywrightBrowserService {

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private Playwright playwright;
    private BrowserContext context;
    private final ReentrantLock executionLock = new ReentrantLock(true);

    public PlaywrightBrowserService() {
        // Removed init() from constructor to enable Lazy Loading
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
        
        // Check if we have verified before
        java.nio.file.Path verifiedMarker = AppPaths.playwrightVerifiedFile();
        boolean isVerified = java.nio.file.Files.exists(verifiedMarker);
        
        System.out.println("Cloudflare Verified Status: " + isVerified + " (Off-screen: " + isVerified + ")");

        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
            .setHeadless(false) // Never truly headless because CF blocks headless Chromium
            .setChannel("msedge");

        java.util.List<String> args = new java.util.ArrayList<>();
        args.add("--disable-blink-features=AutomationControlled");
        if (isVerified) {
            args.add("--window-position=-32000,-32000"); // Hide window off-screen to not bother the user
        }
        options.setArgs(args);

        try {
            java.nio.file.Path dataPath = AppPaths.playwrightDataDir();
            context = playwright.chromium().launchPersistentContext(dataPath, options);
        } catch (Exception e) {
            System.out.println("Failed to launch Edge, trying Chrome...");
            options.setChannel("chrome");
            java.nio.file.Path dataPath = AppPaths.playwrightDataDir();
            context = playwright.chromium().launchPersistentContext(dataPath, options);
        }
        
        System.out.println("Playwright Persistent Browser Context Ready.");
        
        // 注册 JVM 关闭钩子，确保项目停止时自动查杀浏览器进程
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("JVM Shutdown Hook Triggered: Forcibly closing Playwright browser...");
            this.close();
        }));
    }

    /**
     * Call this when we are sure CF is passed (e.g. after successful parse/browse)
     */
    public boolean markAsVerified() {
        try {
            java.nio.file.Path verifiedMarker = AppPaths.playwrightVerifiedFile();
            if (!java.nio.file.Files.exists(verifiedMarker)) {
                java.nio.file.Files.createFile(verifiedMarker);
                System.out.println("Browser marked as VERIFIED. Next launch will be headless.");
                return true;
            }
        } catch (Exception e) {
            System.err.println("Failed to create verification marker: " + e.getMessage());
        }
        return false;
    }

    public synchronized void restartIfNewlyVerified(boolean newlyVerified) {
        if (newlyVerified) {
            System.out.println("Cloudflare verified! Restarting browser off-screen in background...");
            close();
            init();
        }
    }

    public synchronized void forceRestartHeadful() {
        System.out.println("Force restarting browser in HEADFUL mode for verification...");
        try {
            java.nio.file.Path verifiedMarker = AppPaths.playwrightVerifiedFile();
            if (java.nio.file.Files.exists(verifiedMarker)) {
                java.nio.file.Files.delete(verifiedMarker);
            }
        } catch (Exception e) {}
        
        close();
        init(); // This will now launch as headful because marker is gone
    }

    public boolean isHeadless() {
        java.nio.file.Path verifiedMarker = AppPaths.playwrightVerifiedFile();
        return java.nio.file.Files.exists(verifiedMarker);
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

    public <T> T runSerialized(CheckedSupplier<T> action) throws Exception {
        executionLock.lock();
        try {
            return action.get();
        } finally {
            executionLock.unlock();
        }
    }

    @PreDestroy
    public void close() {
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
            java.nio.file.Path dataPath = AppPaths.playwrightDataDir();
            if (java.nio.file.Files.exists(dataPath)) {
                System.out.println("Force clearing browser data directory: " + dataPath);
                deleteDirectoryRecursively(dataPath);
            }
            java.nio.file.Path verifiedMarker = AppPaths.playwrightVerifiedFile();
            if (java.nio.file.Files.exists(verifiedMarker)) {
                java.nio.file.Files.delete(verifiedMarker);
            }
        } catch (Exception e) {
            System.err.println("Failed to clear data directory: " + e.getMessage());
        }
    }

    private void deleteDirectoryRecursively(java.nio.file.Path path) throws java.io.IOException {
        if (java.nio.file.Files.isDirectory(path)) {
            try (java.util.stream.Stream<java.nio.file.Path> entries = java.nio.file.Files.list(path)) {
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
