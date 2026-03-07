package com.wangver.hanime.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangver.hanime.model.AppSettings;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class SettingsManager {

    private final Path settingsFile;
    private final Path legacyConfigFile;
    private final ObjectMapper mapper;
    private AppSettings currentSettings;

    public SettingsManager() {
        this(AppPaths.settingsFile(), AppPaths.legacyConfigFile(), new ObjectMapper());
    }

    SettingsManager(Path settingsFile, Path legacyConfigFile, ObjectMapper mapper) {
        this.settingsFile = settingsFile;
        this.legacyConfigFile = legacyConfigFile;
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        loadSettings();
    }

    public synchronized AppSettings getSettings() {
        return currentSettings;
    }

    public synchronized void saveSettings(AppSettings newSettings) {
        try {
            Path parent = settingsFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            mapper.writeValue(settingsFile.toFile(), newSettings);
            this.currentSettings = newSettings;
        } catch (IOException e) {
            throw new IllegalStateException("无法保存设置文件", e);
        }
    }

    private void loadSettings() {
        if (Files.exists(settingsFile)) {
            try {
                this.currentSettings = mapper.readValue(settingsFile.toFile(), AppSettings.class);
                return;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (Files.exists(legacyConfigFile)) {
            try {
                this.currentSettings = mapper.readValue(legacyConfigFile.toFile(), AppSettings.class);
                saveSettings(this.currentSettings);
                return;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        this.currentSettings = new AppSettings();
        saveSettings(this.currentSettings);
    }
}
