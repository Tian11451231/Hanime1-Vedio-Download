package com.wangver.hanime.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class DownloadTaskView {

    private final String id;
    private final String title;
    private final String pageUrl;
    private final String downloadUrl;
    private final String thumbnail;
    private final String fileName;
    private final String filePath;
    private final DownloadStatus status;
    private final double progressPercent;
    private final long completedAmount;
    private final long totalAmount;
    private final String errorMessage;
    private final String createdAt;
    private final String startedAt;
    private final String finishedAt;

    @JsonCreator
    public DownloadTaskView(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("pageUrl") String pageUrl,
            @JsonProperty("downloadUrl") String downloadUrl,
            @JsonProperty("thumbnail") String thumbnail,
            @JsonProperty("fileName") String fileName,
            @JsonProperty("filePath") String filePath,
            @JsonProperty("status") DownloadStatus status,
            @JsonProperty("progressPercent") double progressPercent,
            @JsonProperty("completedAmount") long completedAmount,
            @JsonProperty("totalAmount") long totalAmount,
            @JsonProperty("errorMessage") String errorMessage,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("startedAt") String startedAt,
            @JsonProperty("finishedAt") String finishedAt
    ) {
        this.id = id;
        this.title = title;
        this.pageUrl = pageUrl;
        this.downloadUrl = downloadUrl;
        this.thumbnail = thumbnail;
        this.fileName = fileName;
        this.filePath = filePath;
        this.status = status;
        this.progressPercent = progressPercent;
        this.completedAmount = completedAmount;
        this.totalAmount = totalAmount;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public String getThumbnail() {
        return thumbnail;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public DownloadStatus getStatus() {
        return status;
    }

    public double getProgressPercent() {
        return progressPercent;
    }

    public long getCompletedAmount() {
        return completedAmount;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public String getFinishedAt() {
        return finishedAt;
    }
}
