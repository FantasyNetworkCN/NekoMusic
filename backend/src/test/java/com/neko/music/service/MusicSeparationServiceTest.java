package com.neko.music.service;

import com.neko.music.util.MusicAssetLocator;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicSeparationServiceTest {

    @Test
    void acceptsThirtyQueuedTasksWithThreeWorkers() throws Exception {
        Path fakeWorker = Files.createTempFile("fake-separation-worker-", ".sh");
        String previousPython = System.getProperty("neko.separation.python");
        String previousWorker = System.getProperty("neko.separation.worker");
        List<Integer> musicIds = new ArrayList<>();
        try {
            writeFakeWorker(fakeWorker, true);
            assertTrue(fakeWorker.toFile().setExecutable(true));
            System.setProperty("neko.separation.python", "python3");
            System.setProperty("neko.separation.worker", fakeWorker.toString());

            List<MusicSeparationService.Job> jobs = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                int musicId = ThreadLocalRandom.current().nextInt(1_000_000_000, 2_000_000_000);
                musicIds.add(musicId);
                writeShortWav(MusicAssetLocator.audioDir().resolve(musicId + ".mp3"));
                jobs.add(MusicSeparationService.submit(musicId));
            }
            for (MusicSeparationService.Job job : jobs) {
                int index = 0;
                MusicSeparationService.Event event;
                boolean done = false;
                while ((event = job.awaitEvent(index++)) != null) {
                    if ("done".equals(event.name())) {
                        done = true;
                    }
                }
                assertTrue(done, "任务未完成: " + job.jobId());
            }
        } finally {
            restore("neko.separation.python", previousPython);
            restore("neko.separation.worker", previousWorker);
            for (int musicId : musicIds) {
                Files.deleteIfExists(MusicAssetLocator.audioDir().resolve(musicId + ".mp3"));
                deleteTree(MusicAssetLocator.derivedDir(musicId));
            }
            Files.deleteIfExists(fakeWorker);
        }
    }

    @Test
    void publishesChunksAndFullMediaThenReusesTheCache() throws Exception {
        int musicId = ThreadLocalRandom.current().nextInt(1_000_000_000, 2_000_000_000);
        Path source = MusicAssetLocator.audioDir().resolve(musicId + ".mp3");
        Path derivedDir = MusicAssetLocator.derivedDir(musicId);
        Path fakeWorker = Files.createTempFile("fake-separation-worker-", ".sh");
        String previousPython = System.getProperty("neko.separation.python");
        String previousWorker = System.getProperty("neko.separation.worker");
        try {
            writeShortWav(source);
            writeFakeWorker(fakeWorker, false);
            assertTrue(fakeWorker.toFile().setExecutable(true));
            System.setProperty("neko.separation.python", "python3");
            System.setProperty("neko.separation.worker", fakeWorker.toString());

            MusicSeparationService.Job job = MusicSeparationService.submit(musicId);
            int index = 0;
            boolean sawChunk = false;
            MusicSeparationService.Event event;
            while ((event = job.awaitEvent(index++)) != null) {
                if ("chunk".equals(event.name())) {
                    sawChunk = true;
                    assertEquals("vocals-v1-000.mp3", event.data().get("vocalsFile"));
                }
            }
            assertTrue(sawChunk);

            MusicSeparationService.Result first = MusicSeparationService.separate(musicId);
            assertEquals("vocals", Files.readString(first.vocals()));
            assertEquals("accompaniment", Files.readString(first.accompaniment()));
            assertEquals(derivedDir.resolve(MusicSeparationService.VOCALS_FILE).toAbsolutePath().normalize(),
                    first.vocals());
            assertEquals(derivedDir.resolve(MusicSeparationService.ACCOMPANIMENT_FILE).toAbsolutePath().normalize(),
                    first.accompaniment());

            Files.writeString(fakeWorker, "#!/bin/sh\nexit 99\n", StandardCharsets.UTF_8);
            MusicSeparationService.Result cached = MusicSeparationService.separate(musicId);
            assertEquals(first, cached);
        } finally {
            restore("neko.separation.python", previousPython);
            restore("neko.separation.worker", previousWorker);
            Files.deleteIfExists(source);
            deleteTree(derivedDir);
            Files.deleteIfExists(fakeWorker);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static void writeFakeWorker(Path path, boolean sleep) throws Exception {
        String sleepLine = sleep ? "import time\ntime.sleep(0.05)\n" : "";
        Files.writeString(path, """
                import json
                import os
                import sys
                %s
                for line in sys.stdin:
                    command = json.loads(line)
                    if command.get("action") == "shutdown":
                        break
                    out = command["outputDir"]
                    os.makedirs(out, exist_ok=True)
                    with open(os.path.join(out, "vocals-000.mp3"), "w") as file:
                        file.write("vocals" if "%s" != "thirty" else "v")
                    with open(os.path.join(out, "accompaniment-000.mp3"), "w") as file:
                        file.write("accompaniment" if "%s" != "thirty" else "a")
                    print('{"event":"start","totalChunks":1,"sampleRate":44100,"device":"cuda","fp16":true,"chunkSeconds":30}', flush=True)
                    print('{"event":"chunk","index":0,"totalChunks":1,"startSec":0,"endSec":0.3,"vocals":"vocals-000.mp3","accompaniment":"accompaniment-000.mp3"}', flush=True)
                    print('{"event":"done","totalChunks":1}', flush=True)
                """.formatted(sleepLine, sleep ? "thirty" : "normal", sleep ? "thirty" : "normal"),
                StandardCharsets.UTF_8);
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
}
