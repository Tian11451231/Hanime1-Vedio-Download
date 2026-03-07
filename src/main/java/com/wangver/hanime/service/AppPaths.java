package com.wangver.hanime.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class AppPaths {

    private static final String APP_HOME_PROPERTY = "hanime.appHome";
    private static final String APP_DIR_NAME = "HanimeMediaCenter";

    private AppPaths() {
    }

    static Path appHome() {
        String override = System.getProperty(APP_HOME_PROPERTY);
        if (override != null && !override.isBlank()) {
            return ensureDirectory(Paths.get(override));
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return ensureDirectory(Paths.get(localAppData).resolve(APP_DIR_NAME));
        }

        return ensureDirectory(Paths.get(System.getProperty("user.home")).resolve("." + APP_DIR_NAME));
    }

    static Path settingsFile() {
        return appHome().resolve("settings.json");
    }

    static Path legacyConfigFile() {
        return appHome().resolve("config.json");
    }

    static Path downloadHistoryFile() {
        return appHome().resolve("download-history.json");
    }

    static Path playwrightDataDir() {
        return appHome().resolve(".playwright_data");
    }

    static Path playwrightVerifiedFile() {
        return appHome().resolve(".playwright_verified");
    }

    private static Path ensureDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            return directory;
        } catch (IOException e) {
            throw new IllegalStateException("无法创建应用数据目录: " + directory, e);
        }
    }
}
