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
        Path file;
        if (parts.length > 1) {
            // 带派生文件名的 URL 只能命中该文件；不能回退成原始音频，否则尚未生成或已被清理的
            // 人声音轨 URL 会静默播放整首原曲。
            file = requested != null
                    && MusicAssetLocator.isUnderDirectory(requested, derivedDir)
                    && requested.getParent().equals(derivedDir.toAbsolutePath().normalize())
                    && requested.getFileName().toString().contains(".")
                    && Files.isRegularFile(requested)
                    ? requested : null;
        } else {
            file = MusicAssetLocator.findAudioFile(musicId).orElse(null);
        }
        if (file == null || !Files.isRegularFile(file) || !MusicAssetLocator.isUnderDirectory(file, MusicAssetLocator.audioDir())) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        send(file, request, response);
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
        if (name.endsWith(".m4a")) return "audio/mp4";
        return "audio/mpeg";
    }
}
