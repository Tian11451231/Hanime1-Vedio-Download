package com.wangver.hanime.service;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class TestHttpResponse<T> implements HttpResponse<T> {

    private final int statusCode;
    private final T body;
    private final HttpHeaders headers;
    private final HttpRequest request;

    TestHttpResponse(int statusCode, T body) {
        this(statusCode, body, Map.of());
    }

    TestHttpResponse(int statusCode, T body, Map<String, List<String>> headers) {
        this.statusCode = statusCode;
        this.body = body;
        this.headers = HttpHeaders.of(headers, (name, value) -> true);
        this.request = HttpRequest.newBuilder(URI.create("https://hanime1.me/")).build();
    }

    @Override
    public int statusCode() {
        return statusCode;
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
        return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
        return headers;
    }

    @Override
    public T body() {
        return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
        return Optional.empty();
    }

    @Override
    public URI uri() {
        return request.uri();
    }

    @Override
    public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
    }
}
