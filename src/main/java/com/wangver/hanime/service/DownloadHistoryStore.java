package com.wangver.hanime.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wangver.hanime.model.DownloadTaskView;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class DownloadHistoryStore {

    private static final TypeReference<List<DownloadTaskView>> HISTORY_TYPE = new TypeReference<>() {
    };

    private final Path historyFile;
    private final ObjectMapper objectMapper;

    public DownloadHistoryStore() {
        this(AppPaths.downloadHistoryFile(), new ObjectMapper().findAndRegisterModules());
    }

    DownloadHistoryStore(Path historyFile, ObjectMapper objectMapper) {
        this.historyFile = historyFile;
        this.objectMapper = objectMapper;
    }

    public synchronized List<DownloadTaskView> load() {
        if (!Files.exists(historyFile)) {
            return new ArrayList<>();
        }

        try {
            return objectMapper.readValue(historyFile.toFile(), HISTORY_TYPE);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public synchronized void save(List<DownloadTaskView> history) {
        try {
            Path parent = historyFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(historyFile.toFile(), history);
        } catch (IOException e) {
            throw new IllegalStateException("无法保存下载历史", e);
        }
    }
}
