package com.wangver.hanime.model;

public class AppSettings {
    private String downloadDirectory;

    public AppSettings() {
        // Default download directory: "Downloads" folder in user's home path
        this.downloadDirectory = System.getProperty("user.home") + "/Downloads/Hanime";
    }

    public String getDownloadDirectory() {
        return downloadDirectory;
    }

    public void setDownloadDirectory(String downloadDirectory) {
        this.downloadDirectory = downloadDirectory;
    }
}
