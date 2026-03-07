package com.wangver.hanime.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPathsTest {

    @TempDir
    Path tempDir;

    @Test
    void settingsManagerDefaultConstructorUsesAppHomeOverride() throws Exception {
        System.setProperty("hanime.appHome", tempDir.toString());
        try {
            SettingsManager manager = new SettingsManager();

            Field settingsField = SettingsManager.class.getDeclaredField("settingsFile");
            settingsField.setAccessible(true);
            Field legacyField = SettingsManager.class.getDeclaredField("legacyConfigFile");
            legacyField.setAccessible(true);

            assertEquals(tempDir.resolve("settings.json"), settingsField.get(manager));
            assertEquals(tempDir.resolve("config.json"), legacyField.get(manager));
        } finally {
            System.clearProperty("hanime.appHome");
        }
    }

    @Test
    void downloadHistoryStoreDefaultConstructorUsesAppHomeOverride() throws Exception {
        System.setProperty("hanime.appHome", tempDir.toString());
        try {
            DownloadHistoryStore store = new DownloadHistoryStore();
            Field historyField = DownloadHistoryStore.class.getDeclaredField("historyFile");
            historyField.setAccessible(true);

            assertEquals(tempDir.resolve("download-history.json"), historyField.get(store));
            assertTrue(((Path) historyField.get(store)).startsWith(tempDir));
        } finally {
            System.clearProperty("hanime.appHome");
        }
    }
}
