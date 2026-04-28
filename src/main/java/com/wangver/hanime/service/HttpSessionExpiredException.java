package com.wangver.hanime.service;

public class HttpSessionExpiredException extends RuntimeException {

    public HttpSessionExpiredException(String message) {
        super(message);
    }

    public HttpSessionExpiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
