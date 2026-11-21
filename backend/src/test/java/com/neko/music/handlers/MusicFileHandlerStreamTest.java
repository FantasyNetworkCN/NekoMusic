package com.neko.music.handlers;

import com.neko.music.service.MusicSeparationService;
import com.neko.music.util.MusicAssetLocator;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicFileHandlerStreamTest {

    @Test
    void streamsChunkAndFinalMediaEvents() throws Exception {
        int musicId = ThreadLocalRandom.current().nextInt(1_000_000_000, 2_000_000_000);
        Path source = MusicAssetLocator.audioDir().resolve(musicId + ".mp3");
        Path derivedDir = MusicAssetLocator.derivedDir(musicId);
        Path fakeWorker = Files.createTempFile("fake-separation-worker-", ".sh");
        String previousPython = System.getProperty("neko.separation.python");
        String previousWorker = System.getProperty("neko.separation.worker");
        try {
            writeShortWav(source);
            Files.writeString(fakeWorker, """
                    import json
                    import os
                    import sys
                    for line in sys.stdin:
                        command = json.loads(line)
                        if command.get("action") == "shutdown":
                            break
                        out = command["outputDir"]
                        os.makedirs(out, exist_ok=True)
                        open(os.path.join(out, "vocals-000.mp3"), "w").write("v")
                        open(os.path.join(out, "accompaniment-000.mp3"), "w").write("a")
                        print('{"event":"start","totalChunks":1,"sampleRate":44100,"device":"cuda","fp16":true,"chunkSeconds":30}', flush=True)
                        print('{"event":"chunk","index":0,"totalChunks":1,"startSec":0,"endSec":0.3,"vocals":"vocals-000.mp3","accompaniment":"accompaniment-000.mp3"}', flush=True)
                        print('{"event":"done","totalChunks":1}', flush=True)
                    """, StandardCharsets.UTF_8);
            assertTrue(fakeWorker.toFile().setExecutable(true));
            System.setProperty("neko.separation.python", "python3");
            System.setProperty("neko.separation.worker", fakeWorker.toString());

            AtomicReference<StringWriter> sink = new AtomicReference<>();
            Map<String, String> headers = new HashMap<>();
            CountDownLatch completed = new CountDownLatch(1);
            HttpServletResponse response = response(sink, headers);
            HttpServletRequest request = request(musicId, completed);

            MusicFileHandler.Dependencies dependencies = new MusicFileHandler.Dependencies(
                    req -> 42,
                    id -> true,
                    id -> true,
                    (id, quality) -> "/tmp/unused.mp3",
                    id -> {
                        throw new AssertionError("同步分离不应被调用");
                    },
                    MusicSeparationService::submit,
                    (id, ip) -> {
                    });

            new MusicFileHandler(dependencies).doGet(request, response);
            assertTrue(completed.await(5, TimeUnit.SECONDS), "SSE 未在超时前结束");
            String text = sink.get().toString();
            assertTrue(text.contains("event: queued"));
            assertTrue(text.contains("event: chunk"));
            assertTrue(text.contains("/media/music/" + musicId + "/vocals-v1-000.mp3"));
            assertTrue(text.contains("event: done"));
            assertTrue(text.contains("/media/music/" + musicId + "/vocals-v1.mp3"));
            assertEquals("text/event-stream;charset=UTF-8", headers.get("content-type"));
            assertEquals("no-cache, no-store, must-revalidate", headers.get("cache-control"));
        } finally {
            restore("neko.separation.python", previousPython);
            restore("neko.separation.worker", previousWorker);
            Files.deleteIfExists(source);
            deleteTree(derivedDir);
            Files.deleteIfExists(fakeWorker);
        }
    }

    private static HttpServletResponse response(AtomicReference<StringWriter> sink,
                                                Map<String, String> headers) {
        StringWriter text = new StringWriter();
        sink.set(text);
        PrintWriter writer = new PrintWriter(text);
        return (HttpServletResponse) Proxy.newProxyInstance(
                MusicFileHandlerStreamTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWriter" -> writer;
                    case "setHeader" -> {
                        headers.put(((String) args[0]).toLowerCase(Locale.ROOT), (String) args[1]);
                        yield null;
                    }
                    case "setContentType" -> {
                        headers.put("content-type", (String) args[0]);
                        yield null;
                    }
                    default -> {
                        Object value = defaultValue(method.getReturnType());
                        if (value != null) {
                            yield value;
                        }
                        yield null;
                    }
                });
    }

    private static HttpServletRequest request(int musicId, CountDownLatch completed) {
        AsyncContext asyncContext = (AsyncContext) Proxy.newProxyInstance(
                MusicFileHandlerStreamTest.class.getClassLoader(),
                new Class<?>[]{AsyncContext.class},
                (proxy, method, args) -> {
                    if ("complete".equals(method.getName())) {
                        completed.countDown();
                    }
                    return defaultValue(method.getReturnType());
                });
        return (HttpServletRequest) Proxy.newProxyInstance(
                MusicFileHandlerStreamTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPathInfo" -> "/" + musicId + "/stream";
                    case "getParameter" -> "separate".equals(args[0]) ? "1" : null;
                    case "getHeader" -> null;
                    case "getRemoteAddr" -> "127.0.0.1";
                    case "startAsync" -> asyncContext;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static void writeShortWav(Path target) throws Exception {
        AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
        byte[] silence = new byte[44100 * 2 * 2 / 10];
        try (AudioInputStream input = new AudioInputStream(
                new ByteArrayInputStream(silence), format, silence.length / format.getFrameSize())) {
            Files.createDirectories(target.getParent());
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, target.toFile());
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return 0;
    }
}
