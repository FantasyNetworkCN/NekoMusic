package com.neko.music.handlers;

import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.MusicAssetLocator;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

/** Serves the stable, CDN-cacheable target of the music redirect. */
public final class MediaMusicHandler extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if (path == null || !path.startsWith("/") || path.length() < 3) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String[] parts = path.substring(1).split("/", 2);
        int musicId;
        try {
            musicId = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        Path derivedDir = MusicAssetLocator.derivedDir(musicId);
        Path requested = parts.length > 1 ? derivedDir.resolve(parts[1]).normalize() : null;
        Path source = MusicAssetLocator.findAudioFile(musicId).orElse(null);
        Path file = resolveFile(derivedDir, requested, source);
        if (file == null || !Files.isRegularFile(file) || !MusicAssetLocator.isUnderDirectory(file, MusicAssetLocator.audioDir())) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        send(file, request, response);
    }

    /**
     * 兼容旧客户端使用 {@code /media/music/{id}/{原文件名}} 播放原曲，同时确保不存在的派生文件
     * 不会误回退成原曲。只有请求文件名与当前原曲文件名完全一致时才允许回退。
     */
    static Path resolveFile(Path derivedDir, Path requested, Path source) {
        if (requested == null) {
            return source;
        }
        Path normalizedDerived = derivedDir.toAbsolutePath().normalize();
        Path normalizedRequested = requested.toAbsolutePath().normalize();
        if (MusicAssetLocator.isUnderDirectory(normalizedRequested, normalizedDerived)
                && normalizedRequested.getParent().equals(normalizedDerived)
                && normalizedRequested.getFileName().toString().contains(".")
                && Files.isRegularFile(normalizedRequested)) {
            return normalizedRequested;
        }
        if (source != null
                && source.getFileName().equals(normalizedRequested.getFileName())) {
            return source;
        }
        return null;
    }

    private void send(Path file, HttpServletRequest request, HttpServletResponse response) throws IOException {
        // 长度、ETag、Last-Modified 共用同一次 stat
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        long size = attrs.size();
        String etag = HttpResourceCache.etagFor(attrs);
        if (request.getHeader("Range") == null && HttpResourceCache.sendNotModifiedIfFresh(request, response, etag)) {
            HttpResourceCache.setAcceptRangesBytes(response);
            return;
        }
        String contentType = contentType(file);
        String range = request.getHeader("Range");
        HttpResourceCache.setAcceptRangesBytes(response);
        HttpResourceCache.applyFileCachingHeaders(attrs, response, HttpResourceCache.CACHE_CONTROL_FILE);
        response.setContentType(contentType);
        if (range == null || !range.startsWith("bytes=")) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLengthLong(size);
            try (InputStream in = Files.newInputStream(file); OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
            }
            return;
        }
        String[] values = range.substring(6).split("-", 2);
        long start = Long.parseLong(values[0]);
        long end = values.length > 1 && !values[1].isBlank() ? Long.parseLong(values[1]) : size - 1;
        if (start < 0 || start >= size || end < start) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader("Content-Range", "bytes */" + size);
            return;
        }
        end = Math.min(end, size - 1);
        long length = end - start + 1;
        response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
        response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        response.setContentLengthLong(length);
        try (var input = Files.newInputStream(file); OutputStream out = response.getOutputStream()) {
            input.skipNBytes(start);
            byte[] buffer = new byte[65536];
            long remaining = length;
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) break;
                out.write(buffer, 0, count);
                remaining -= count;
            }
        }
    }

    private String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".flac")) return "audio/flac";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".m4a") || name.endsWith(".mp4")) return "audio/mp4";
        if (name.endsWith(".aac")) return "audio/aac";
        if (name.endsWith(".ogg") || name.endsWith(".oga") || name.endsWith(".opus")) return "audio/ogg";
        if (name.endsWith(".ape")) return "audio/x-ape";
        if (name.endsWith(".wma")) return "audio/x-ms-wma";
        if (name.endsWith(".aiff") || name.endsWith(".aif")) return "audio/aiff";
        if (name.endsWith(".eac3") || name.endsWith(".ec3")) return "audio/eac3";
        if (name.endsWith(".ac3")) return "audio/ac3";
        if (name.endsWith(".thd") || name.endsWith(".truehd") || name.endsWith(".mlp")) return "audio/truehd";
        if (name.endsWith(".mka")) return "audio/x-matroska";
        if (name.endsWith(".webm")) return "audio/webm";
        if (name.endsWith(".dts")) return "audio/vnd.dts";
        if (name.endsWith(".wv")) return "audio/wavpack";
        if (name.endsWith(".caf")) return "audio/x-caf";
        return "audio/mpeg";
    }
}
