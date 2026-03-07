package com.wangver.hanime.model;

public record DownloadProgress(long completedAmount, long totalAmount) {

    public double percent() {
        if (totalAmount <= 0) {
            return 0.0;
        }
        return (completedAmount * 100.0) / totalAmount;
    }
}
