package com.wangver.hanime.model;

import java.util.ArrayList;
import java.util.List;

public class DownloadBatchRequest {

    private List<DownloadRequestItem> items = new ArrayList<>();

    public List<DownloadRequestItem> getItems() {
        return items;
    }

    public void setItems(List<DownloadRequestItem> items) {
        this.items = items;
    }
}
